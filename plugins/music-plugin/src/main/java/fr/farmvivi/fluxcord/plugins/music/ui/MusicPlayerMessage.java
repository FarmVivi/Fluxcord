package fr.farmvivi.fluxcord.plugins.music.ui;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.util.ProgressBar;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the persistent music player message with controls and status.
 */
public class MusicPlayerMessage {
    private static final Logger logger = LoggerFactory.getLogger(MusicPlayerMessage.class);
    private static final String BUTTON_PREFIX = "music:";
    /**
     * The shortest gap allowed between two edits of the player message.
     *
     * <p>MEASURED 2026-10-04: editing this message more often earns a steady stream of 429s, each answered
     * by Discord with a {@code Retry-After} of five to six seconds - so that, and not 500 ms, is what the
     * route actually allows. The old value let a burst (a model queueing several tracks, a track starting
     * and ending) ask for two edits a second, which built a backlog that took minutes to drain and kept
     * being refilled while it drained.
     *
     * <p>Comfortably under {@link #PROGRESS_UPDATE_INTERVAL_MS}, so the progress refresh is unaffected: this
     * only collapses bursts.
     */
    private static final int MIN_EDIT_INTERVAL_MS = 5_000;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 10_000; // 10s

    private final MusicPlayer musicPlayer;
    private final PluginLanguageAdapter lang;

    private MessageChannel messageChannel;
    private Message message;
    private Long messageId;
    private Long channelId;

    private volatile ScheduledFuture<?> updateTask;
    private ScheduledFuture<?> progressUpdateTask;
    private long lastUpdateTime = 0;
    private volatile boolean controlsDirty = false;
    // True while an existing player message is being retrieved from Discord on restore, to avoid
    // creating a duplicate message before the retrieval resolves.
    private volatile boolean awaitingMessageRestore = false;

    public MusicPlayerMessage(MusicPlayer musicPlayer) {
        this.musicPlayer = musicPlayer;
        this.lang = musicPlayer.getPlugin().getLanguage();

        // Try to restore message from storage
        restoreMessage();
    }

    /**
     * Parses a button ID to extract guild ID and action.
     */
    public static ButtonInfo parseButtonId(String buttonId) {
        if (!buttonId.startsWith(BUTTON_PREFIX)) {
            return null;
        }

        String[] parts = buttonId.substring(BUTTON_PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            return null;
        }

        return new ButtonInfo(parts[0], parts[1]);
    }

    /**
     * Refresh request: marque les contrôles comme modifiés et déclenche un rendu différé.
     */
    public void refresh() {
        controlsDirty = true;
        scheduleDelayedUpdate();
    }

    /**
     * Effectue le rendu réel si nécessaire (debounced + throttle).
     */
    private void renderNow() {
        // Cleared first, so a render this one suppresses can arm another. Before, the guard in
        // scheduleDelayedUpdate looked at a task that was still running - never "done" - and quietly
        // scheduled nothing, so an update the throttle turned away was simply lost and the message sat
        // stale until the next unrelated event.
        updateTask = null;
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < MIN_EDIT_INTERVAL_MS) {
            scheduleDelayedUpdate();
            return;
        }
        lastUpdateTime = now;

        Guild guild = musicPlayer.getGuild();
        AudioTrack track = musicPlayer.getPlayingTrack();

        // Si non connecté ET rien à afficher, nettoyer; sinon continuer le rendu
        boolean connected = guild.getAudioManager().isConnected();
        boolean hasQueue = musicPlayer.getTrackScheduler().getQueueSize() > 0;
        if (!connected && track == null && !hasQueue) {
            delete();
            stopProgressUpdates();
            return;
        }

        // N'update que si en lecture (progression) ou si un contrôle a changé
        boolean isPlaying = track != null && !musicPlayer.isPaused();
        if (!isPlaying && !controlsDirty) {
            return;
        }

        EmbedBuilder embed = createEmbed(track);
        List<MessageTopLevelComponent> actionRows = createActionRows(track);

        if (messageChannel == null) {
            return;
        }

        // Si on a déjà un message, vérifier s'il est encore le dernier du salon
        if (message != null) {
            // The "am I still the last message?" check costs a request of its own, so it is only worth
            // making when something actually changed. A progress tick changes a bar inside an embed nobody
            // is scrolling past; paying double for every one of those is how a refresh ends up costing two
            // requests instead of one.
            if (!controlsDirty) {
                edit(embed, actionRows);
            } else {
                messageChannel.getHistory().retrievePast(1).queue(latest -> {
                    boolean isLast = !latest.isEmpty() && latest.get(0).getIdLong() == message.getIdLong();
                    if (isLast) {
                        edit(embed, actionRows);
                    } else {
                        createNewMessage(embed, actionRows);
                    }
                }, err -> edit(embed, actionRows));
            }
        } else if (!awaitingMessageRestore) {
            createNewMessage(embed, actionRows);
        }

        // S'assurer que le rafraîchissement périodique fonctionne
        startProgressUpdates();
    }

    /**
     * Edits the player message in place.
     *
     * <p><strong>A failed edit does not post a new message.</strong> It used to, and that is what turned
     * being throttled into a loop: an edit rejected with 429 answered by sending a message and deleting the
     * old one, so each failure produced <em>more</em> traffic than the edit it replaced - a POST, a DELETE
     * and then another GET and PATCH on the next tick. The log showed ten rejections a minute for five
     * minutes with no let-up. Reposting is right for exactly one failure, the message being gone; anything
     * else is left alone, the controls stay marked dirty, and the next tick tries again.
     */
    private void edit(EmbedBuilder embed, List<MessageTopLevelComponent> actionRows) {
        MessageEditBuilder editBuilder = new MessageEditBuilder()
                .setEmbeds(embed.build())
                .setComponents(actionRows);
        message.editMessage(editBuilder.build()).queue(
                m -> {
                    message = m;
                    controlsDirty = false;
                },
                failure -> {
                    if (failure instanceof ErrorResponseException error
                            && (error.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE
                            || error.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL)) {
                        logger.debug("Player message is gone in guild {}; posting a new one",
                                musicPlayer.getGuild().getId());
                        message = null;
                        createNewMessage(embed, actionRows);
                        return;
                    }
                    // Deliberately not retried here: the periodic refresh is the retry, and it comes with
                    // the interval this route tolerates.
                    logger.debug("Could not edit the player message in guild {}: {}",
                            musicPlayer.getGuild().getId(), failure.getMessage());
                }
        );
    }

    /**
     * Creates the embed for the player message.
     */
    private EmbedBuilder createEmbed(AudioTrack track) {
        EmbedBuilder embed = new EmbedBuilder();
        java.util.Locale locale = musicPlayer.getPlugin().getLanguage().getDefaultLocale();

        if (track == null) {
            embed.setTitle(lang.getString(locale, "music.player.no_track"))
                    .setColor(Color.RED);
        } else if (musicPlayer.isPaused()) {
            embed.setTitle(lang.getString(locale, "music.player.paused"))
                    .setColor(Color.ORANGE);
        } else {
            embed.setTitle(lang.getString(locale, "music.player.playing"))
                    .setColor(Color.GREEN);
        }

        if (track != null) {
            // Thumbnail
            if (track.getInfo().artworkUrl != null) {
                embed.setThumbnail(track.getInfo().artworkUrl);
            }

            // Track info
            embed.addField(
                    lang.getString(locale, "music.player.track"),
                    String.format("[%s](%s)", track.getInfo().title, track.getInfo().uri),
                    false
            );

            // Progress bar
            if (track.getDuration() != Long.MAX_VALUE) {
                String progressBar = createProgressBar(track);
                String timeInfo = String.format("%s / %s",
                        TimeParser.formatTime(track.getPosition()),
                        TimeParser.formatTime(track.getDuration())
                );
                embed.addField(
                        lang.getString(locale, "music.player.progress"),
                        progressBar + "\n" + timeInfo,
                        false
                );
            }

            // Queue info
            int queueSize = musicPlayer.getTrackScheduler().getQueueSize();
            if (queueSize > 0) {
                StringBuilder queueInfo = new StringBuilder();

                if (musicPlayer.getTrackScheduler().isShuffleMode()) {
                    queueInfo.append(lang.getString(locale, "music.player.shuffle_mode"));
                } else {
                    List<AudioTrack> queue = musicPlayer.getTrackScheduler().getQueue();
                    int displayed = Math.min(5, queue.size());

                    for (int i = 0; i < displayed; i++) {
                        AudioTrack queueTrack = queue.get(i);
                        queueInfo.append(String.format("%d. [%s](%s)%n",
                                i + 1,
                                queueTrack.getInfo().title,
                                queueTrack.getInfo().uri
                        ));
                    }

                    if (queue.size() > displayed) {
                        queueInfo.append(lang.getString(locale, "music.player.more_tracks", queue.size() - displayed));
                    }
                }

                embed.addField(
                        lang.getString(locale, "music.player.queue", queueSize),
                        queueInfo.toString(),
                        false
                );
            }

            // Playback modes
            List<String> modes = new ArrayList<>();
            if (musicPlayer.getTrackScheduler().isLoopMode()) {
                modes.add(lang.getString(locale, "music.player.loop"));
            }
            if (musicPlayer.getTrackScheduler().isLoopQueueMode()) {
                modes.add(lang.getString(locale, "music.player.loop_queue"));
            }
            if (musicPlayer.getTrackScheduler().isShuffleMode()) {
                modes.add(lang.getString(locale, "music.player.shuffle"));
            }

            if (!modes.isEmpty()) {
                embed.addField(
                        lang.getString(locale, "music.player.modes"),
                        String.join(" • ", modes),
                        false
                );
            }
        }

        return embed;
    }

    /**
     * Creates a progress bar for the current track.
     */
    private String createProgressBar(AudioTrack track) {
        return ProgressBar.of(track.getPosition(), track.getDuration());
    }

    /**
     * Creates action rows with control buttons.
     */
    private List<MessageTopLevelComponent> createActionRows(AudioTrack track) {
        List<MessageTopLevelComponent> rows = new ArrayList<>();

        // Row 1: Main controls
        List<Button> row1 = new ArrayList<>();
        row1.add(Button.primary(getButtonId("add"), "🆕"));

        if (track != null) {
            if (musicPlayer.isPaused()) {
                row1.add(Button.primary(getButtonId("pause"), "▶️"));
            } else {
                row1.add(Button.primary(getButtonId("pause"), "⏸️"));
            }
        }

        if (musicPlayer.getTrackScheduler().getQueueSize() > 0) {
            row1.add(Button.primary(getButtonId("skip"), "⏭️"));
        }

        if (track != null) {
            row1.add(Button.secondary(getButtonId("stop"), "⏹️"));
        }

        if (musicPlayer.getTrackScheduler().getQueueSize() > 0) {
            row1.add(Button.secondary(getButtonId("clear"), "🗑️"));
        }

        if (!row1.isEmpty()) {
            rows.add(ActionRow.of(row1));
        }

        // Row 2: Playback modes
        List<Button> row2 = new ArrayList<>();

        if (musicPlayer.getTrackScheduler().isLoopMode()) {
            row2.add(Button.success(getButtonId("loop"), "🔂"));
        } else {
            row2.add(Button.danger(getButtonId("loop"), "🔂"));
        }

        if (musicPlayer.getTrackScheduler().isLoopQueueMode()) {
            row2.add(Button.success(getButtonId("loopqueue"), "🔁"));
        } else {
            row2.add(Button.danger(getButtonId("loopqueue"), "🔁"));
        }

        if (musicPlayer.getTrackScheduler().isShuffleMode()) {
            row2.add(Button.success(getButtonId("shuffle"), "🔀"));
        } else {
            row2.add(Button.danger(getButtonId("shuffle"), "🔀"));
        }

        if (!row2.isEmpty()) {
            rows.add(ActionRow.of(row2));
        }

        // Row 3: Volume controls
        List<Button> row3 = new ArrayList<>();

        row3.add(Button.secondary(getButtonId("volume:-10"), "-10%"));
        row3.add(Button.secondary(getButtonId("volume:-5"), "-5%"));

        if (musicPlayer.getVolume() == 0) {
            row3.add(Button.danger(getButtonId("mute"), "🔇"));
        } else {
            row3.add(Button.success(getButtonId("mute"), "🔊 " + musicPlayer.getVolume() + "%"));
        }

        row3.add(Button.secondary(getButtonId("volume:+5"), "+5%"));
        row3.add(Button.secondary(getButtonId("volume:+10"), "+10%"));

        rows.add(ActionRow.of(row3));

        return rows;
    }

    /**
     * Creates a new player message.
     */
    private void createNewMessage(EmbedBuilder embed, List<MessageTopLevelComponent> actionRows) {
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setEmbeds(embed.build())
                .setComponents(actionRows);

        messageChannel.sendMessage(builder.build()).queue(m -> {
            if (message != null) {
                message.delete().queue(null, e -> {
                });
            }
            message = m;
            messageId = m.getIdLong();
            channelId = m.getChannel().getIdLong();
            saveMessage();
            startProgressUpdates();
            controlsDirty = false;
        }, failure -> logger.debug("Could not post the player message in guild {}: {}",
                musicPlayer.getGuild().getId(), failure.getMessage()));
    }

    /**
     * Schedules a delayed update to avoid spamming.
     */
    private synchronized void scheduleDelayedUpdate() {
        if (updateTask != null && !updateTask.isDone()) {
            return; // one is already queued; it will render whatever the state is by then
        }

        ScheduledExecutorService scheduler = musicPlayer.getPlugin().getScheduler();
        if (scheduler == null || scheduler.isShutdown()) {
            return; // plugin is disabling: no UI refresh for the final track-end events
        }
        // Waits out whatever is left of the interval rather than a fixed delay, so a render that arrives
        // just after an edit is not re-queued over and over at a rate the route rejects.
        long waited = System.currentTimeMillis() - lastUpdateTime;
        long delay = Math.max(0, MIN_EDIT_INTERVAL_MS - waited);
        try {
            updateTask = scheduler.schedule(this::renderNow, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // shut down between the check and the submit
        }
    }

    /**
     * Starts periodic progress updates (to update progress bar and UI).
     */
    private void startProgressUpdates() {
        if (progressUpdateTask != null && !progressUpdateTask.isDone()) {
            return;
        }
        if (messageChannel == null) {
            return;
        }
        ScheduledExecutorService scheduler = musicPlayer.getPlugin().getScheduler();
        progressUpdateTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                // Only refresh periodically while the music is actively playing
                AudioTrack track = musicPlayer.getPlayingTrack();
                if (track != null && !musicPlayer.isPaused()) {
                    scheduleDelayedUpdate();
                }
            } catch (Exception e) {
                logger.debug("Periodic refresh failed for guild {}", musicPlayer.getGuild().getId(), e);
            }
        }, PROGRESS_UPDATE_INTERVAL_MS, PROGRESS_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops periodic progress updates.
     */
    private void stopProgressUpdates() {
        if (progressUpdateTask != null && !progressUpdateTask.isDone()) {
            progressUpdateTask.cancel(false);
        }
        progressUpdateTask = null;
    }

    /**
     * Generates a button ID with guild context.
     */
    private String getButtonId(String action) {
        return BUTTON_PREFIX + musicPlayer.getGuild().getId() + ":" + action;
    }

    /**
     * Sets the message channel for the player message.
     */
    public void setMessageChannel(MessageChannel channel) {
        this.messageChannel = channel;
        this.channelId = channel.getIdLong();
        refresh();
        startProgressUpdates();
    }

    /**
     * Gets the ID of the text channel hosting the player message, if known.
     *
     * @return the channel ID, or {@code null} if no channel is set
     */
    public Long getChannelId() {
        return channelId;
    }

    /**
     * Deletes the player message.
     */
    public void delete() {
        if (message != null) {
            message.delete().queue(null, e -> {
            });
            message = null;
        }
        messageId = null;
        channelId = null;
        awaitingMessageRestore = false;
        saveMessage();
        stopUpdates();
    }

    /**
     * Stops all UI timers without deleting the Discord message or clearing its stored IDs.
     * Used on graceful shutdown so the existing message can be reused (edited) after a restart.
     */
    public void stopUpdates() {
        if (updateTask != null && !updateTask.isDone()) {
            updateTask.cancel(false);
        }
        updateTask = null;
        stopProgressUpdates();
    }

    /**
     * Saves message ID and channel ID to storage.
     */
    private void saveMessage() {
        String guildId = musicPlayer.getGuild().getId();
        ScopedStorage guildStorage = musicPlayer.getPlugin().getStorage().getGuildStorage(guildId);
        // Persist Discord snowflakes as Strings: stored as JSON numbers they would be reloaded as
        // doubles and lose precision (a 19-digit ID gets rounded), breaking message retrieval.
        if (messageId != null) {
            guildStorage.set("player_messages.message_id", String.valueOf(messageId));
        } else {
            guildStorage.remove("player_messages.message_id");
        }
        if (channelId != null) {
            guildStorage.set("player_messages.channel_id", String.valueOf(channelId));
        } else {
            guildStorage.remove("player_messages.channel_id");
        }
        musicPlayer.getPlugin().getStorage().saveAll();
    }

    /**
     * Parses a stored snowflake ID, tolerating legacy/corrupt values.
     *
     * @param value the stored string value (may be null)
     * @return the parsed ID, or {@code null} if absent or unparseable
     */
    private static Long parseIdOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Restores message from storage.
     */
    private void restoreMessage() {
        String guildId = musicPlayer.getGuild().getId();
        ScopedStorage guildStorage = musicPlayer.getPlugin().getStorage().getGuildStorage(guildId);
        Long storedMessageId = parseIdOrNull(guildStorage.get("player_messages.message_id", String.class).orElse(null));
        Long storedChannelId = parseIdOrNull(guildStorage.get("player_messages.channel_id", String.class).orElse(null));

        if (storedMessageId != null && storedChannelId != null) {
            this.messageId = storedMessageId;
            this.channelId = storedChannelId;

            // Try to retrieve the channel and message
            try {
                MessageChannel channel = musicPlayer.getGuild().getTextChannelById(channelId);
                if (channel != null) {
                    this.messageChannel = channel;
                    // Block message creation until this retrieval resolves, to avoid duplicates.
                    this.awaitingMessageRestore = true;
                    channel.retrieveMessageById(messageId).queue(
                            m -> {
                                this.message = m;
                                this.awaitingMessageRestore = false;
                                startProgressUpdates();
                                // Re-render to reflect the restored state on the existing message.
                                refresh();
                            },
                            e -> {
                                // Message not found: clear stored IDs and allow a fresh one.
                                this.messageId = null;
                                this.channelId = null;
                                this.awaitingMessageRestore = false;
                                saveMessage();
                                refresh();
                            }
                    );
                }
            } catch (Exception e) {
                this.awaitingMessageRestore = false;
                logger.debug("Failed to restore player message for guild {}", guildId, e);
            }
        }
    }

    /**
     * Button info extracted from button ID.
     */
    public record ButtonInfo(String guildId, String action) {
    }
}