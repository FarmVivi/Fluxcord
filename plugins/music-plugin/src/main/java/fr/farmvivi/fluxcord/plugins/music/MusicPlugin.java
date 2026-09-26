package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.plugins.music.commands.*;
import fr.farmvivi.fluxcord.plugins.music.events.MusicButtonListener;
import fr.farmvivi.fluxcord.plugins.music.events.MusicModalListener;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.events.MusicReadyListener;
import fr.farmvivi.fluxcord.plugins.music.events.MusicVoiceListener;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistManager;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope;
import net.dv8tion.jda.api.JDA;

import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Advanced music bot plugin for Fluxcord.
 * <p>
 * Features:
 * - Play music from YouTube, Spotify, SoundCloud, Deezer, Apple Music
 * - Advanced queue management with shuffle, loop, and priority
 * - Persistent music player messages with interactive controls
 * - Playlist management (personal and server playlists)
 * - Audio effects and volume control
 * - Multi-language support via Fluxcord i18n API
 * - Automatic disconnection after inactivity
 */
public class MusicPlugin extends AbstractPlugin {

    private MusicManager musicManager;
    private PlaylistManager playlistManager;
    private ScheduledExecutorService scheduler;

    // Playback-state persistence settings (loaded from configuration)
    private boolean persistenceEnabled = true;
    private long persistenceTtlMillis = 3_600_000L; // 1 hour
    private long autoLeaveTimeoutMs = 300_000L; // 5 minutes

    @Override
    public void onEnable() {
        logger.info("Music Plugin enabling...");

        // Initialize scheduler
        this.scheduler = Executors.newScheduledThreadPool(2);

        // Register permissions
        registerPermissions();

        // Initialize managers
        this.musicManager = new MusicManager(this);
        this.playlistManager = new PlaylistManager(this);

        // Register commands
        registerCommands();

        // Load configuration
        loadConfiguration();

        // Discord events go through JDA listeners (registered on the builder or the live JDA by the core, and
        // removed automatically when the plugin is disabled)
        try {
            addDiscordListeners(new MusicButtonListener(this), new MusicModalListener(this),
                    new MusicReadyListener(this), new MusicVoiceListener(this));
            JDA jda = getContext().getDiscordAPI().getJDA();
            if (jda != null) {
                // JDA already connected (e.g. plugin hot-reload): ReadyEvent won't fire again,
                // so restore persisted playback right away.
                if (jda.getStatus() == JDA.Status.CONNECTED) {
                    scheduler.execute(() -> musicManager.restoreAllStates(jda));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to register JDA listeners for MusicPlugin", e);
        }

        logger.info("Music Plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        logger.info("Music Plugin disabling...");

        // Persist playback state before tearing anything down, so the bot resumes where it
        // left off after a restart (e.g. Kubernetes pod rescheduling).
        if (musicManager != null) {
            musicManager.saveAllStates();
        }

        // Players first (their track-end events still use the scheduler for UI refreshes), then the scheduler
        if (musicManager != null) {
            musicManager.shutdown();
        }

        if (scheduler != null) {
            scheduler.shutdown();
        }

        logger.info("Music Plugin disabled!");
    }

    /**
     * The permission nodes, named once.
     *
     * <p>Public because {@code ButtonHandler} checks the same nodes: a permission protects an action, not
     * the way it was invoked, so the button and the command must name the same one. They are the short
     * node; {@link #permissionKey} turns one into the registered {@code <pluginId>.<node>}.
     */
    /** The {@code /playlist} option naming which store to act on. */
    private static final String OPTION_SCOPE = "scope";

    public static final String PERM_PLAY = "play";
    public static final String PERM_SKIP = "skip";
    public static final String PERM_QUEUE = "queue";
    public static final String PERM_VOLUME = "volume";
    public static final String PERM_PLAYLIST = "playlist";
    public static final String PERM_ADMIN = "admin";

    private void registerPermissions() {
        registerPermissionNode(PERM_PLAY, "Allows playing tracks", PermissionDefault.TRUE);
        registerPermissionNode(PERM_SKIP, "Allows skipping current track", PermissionDefault.TRUE);
        registerPermissionNode(PERM_QUEUE, "Allows viewing and reordering the queue", PermissionDefault.TRUE);
        registerPermissionNode(PERM_VOLUME, "Allows changing playback volume", PermissionDefault.OP);
        registerPermissionNode(PERM_PLAYLIST, "Allows managing playlists", PermissionDefault.TRUE);
        registerPermissionNode(PERM_ADMIN, "Allows moderator music actions", PermissionDefault.OP);
    }

    private void registerPermissionNode(String node, String description, PermissionDefault def) {
        getPermissions().registerPermission(new SimplePermission(permissionKey(node), description, def));
    }

    /** @return the fully qualified name of one of this plugin's permission nodes */
    public String permissionKey(String node) {
        return getId() + "." + node;
    }

    /**
     * Registers a command of the {@code Music} category whose description comes from
     * {@code music.command.<name>.description}.
     *
     * @param name       the command name
     * @param configurer adds the aliases, options and executor
     */
    private void musicCommand(String name, Consumer<CommandBuilder> configurer) {
        getCommands().registerCommand(builder -> {
            builder.name(name)
                    .description(text("music.command." + name + ".description"))
                    .category("Music");
            configurer.accept(builder);
        });
    }

    /** @return one of this plugin's translated strings */
    private String text(String key) {
        return getLanguage().getString(key);
    }

    private void registerCommands() {
        musicCommand("play", builder -> {
            builder
                    .permission(permissionKey(PERM_PLAY))
                    .aliases("p")
                    .stringOption("query", text("music.command.play.option.query"), true, this::suggestRecentTracks)
                    .booleanOption("now", text("music.command.play.option.now"), false)
                    .executor((ctx, cmd) -> {
                        String query = ctx.getRequiredOption("query");
                        boolean playNow = ctx.getOption("now", false);
                        new PlayCommand(this).execute(ctx, query, playNow);
                        return CommandResult.success();
                    });
        });

        musicCommand("pause", builder -> {
            builder
                    .permission(permissionKey(PERM_PLAY))
                    .executor((ctx, cmd) -> {
                        new PauseCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("skip", builder -> {
            builder
                    .permission(permissionKey(PERM_SKIP))
                    .aliases("s", "next")
                    .executor((ctx, cmd) -> {
                        new SkipCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("stop", builder -> {
            builder
                    .permission(permissionKey(PERM_PLAY))
                    .executor((ctx, cmd) -> {
                        new StopCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("queue", builder -> {
            builder
                    .permission(permissionKey(PERM_QUEUE))
                    .aliases("q")
                    .integerOption("page", text("music.command.queue.option.page"), false, 1, 100, this::suggestQueuePages)
                    .executor((ctx, cmd) -> {
                        int page = ctx.getOption("page", 1);
                        new QueueCommand(this).execute(ctx, page);
                        return CommandResult.success();
                    });
        });

        musicCommand("nowplaying", builder -> {
            builder
                    .permission(permissionKey(PERM_QUEUE))
                    .aliases("np", "current")
                    .executor((ctx, cmd) -> {
                        new NowPlayingCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("volume", builder -> {
            builder
                    .permission(permissionKey(PERM_VOLUME))
                    .aliases("vol")
                    .integerOption("level", text("music.command.volume.option.level"), false, 0, 100, this::suggestVolumes)
                    .executor((ctx, cmd) -> {
                        Integer level = ctx.<Integer>getOption("level").orElse(null);
                        new VolumeCommand(this).execute(ctx, level);
                        return CommandResult.success();
                    });
        });

        musicCommand("loop", builder -> {
            builder
                    .permission(permissionKey(PERM_QUEUE))
                    .stringOption(
                            "mode",
                            text("music.command.loop.option.mode"),
                            false,
                            OptionChoice.of(text("music.command.loop.mode.off"), "off"),
                            OptionChoice.of(text("music.command.loop.mode.track"), "track"),
                            OptionChoice.of(text("music.command.loop.mode.queue"), "queue")
                    )
                    .executor((ctx, cmd) -> {
                        String mode = ctx.getOption("mode", "toggle");
                        new LoopCommand(this).execute(ctx, mode);
                        return CommandResult.success();
                    });
        });

        musicCommand("shuffle", builder -> {
            builder
                    .permission(permissionKey(PERM_QUEUE))
                    .executor((ctx, cmd) -> {
                        new ShuffleCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("clear", builder -> {
            builder.permission(permissionKey(PERM_ADMIN))
                    .executor((ctx, cmd) -> {
                        new ClearCommand(this).execute(ctx);
                        return CommandResult.success();
                    });
        });

        musicCommand("remove", builder -> {
            builder
                    .permission(permissionKey(PERM_QUEUE))
                    .integerOption("position", text("music.command.remove.option.position"), true, 1, 1000, this::suggestQueuePositions)
                    .executor((ctx, cmd) -> {
                        int position = ctx.getRequiredOption("position");
                        new RemoveCommand(this).execute(ctx, position);
                        return CommandResult.success();
                    });
        });

        musicCommand("seek", builder -> {
            builder
                    .permission(permissionKey(PERM_PLAY))
                    .stringOption("time", text("music.command.seek.option.time"), true, this::suggestSeekPositions)
                    .executor((ctx, cmd) -> {
                        String time = ctx.getRequiredOption("time");
                        new SeekCommand(this).execute(ctx, time);
                        return CommandResult.success();
                    });
        });

        musicCommand("playlist", builder -> {
            builder.aliases("pl")
                    .permission(permissionKey(PERM_PLAYLIST))
                    .stringOption("action", text("music.command.playlist.option.action"), true,
                            OptionChoice.of(text("music.command.playlist.action.save"), "save"),
                            OptionChoice.of(text("music.command.playlist.action.load"), "load"),
                            OptionChoice.of(text("music.command.playlist.action.list"), "list"),
                            OptionChoice.of(text("music.command.playlist.action.show"), "show"),
                            OptionChoice.of(text("music.command.playlist.action.delete"), "delete"))
                    .stringOption("name", text("music.command.playlist.option.name"), false,
                            this::suggestPlaylistNames)
                    .stringOption(OPTION_SCOPE, text("music.command.playlist.option.scope"), false,
                            OptionChoice.of(text("music.command.playlist.scope.personal"), "personal"),
                            OptionChoice.of(text("music.command.playlist.scope.server"), "server"))
                    .executor((ctx, cmd) -> {
                        String action = ctx.getRequiredOption("action");
                        String name = ctx.<String>getOption("name").orElse(null);
                        String scope = ctx.<String>getOption(OPTION_SCOPE).orElse(null);
                        new PlaylistCommand(this).execute(ctx, action, name, scope);
                        return CommandResult.success();
                    });
        });
    }

    private void loadConfiguration() {
        int defaultVolume = getConfiguration().getInt("music.default_volume", MusicPlayer.DEFAULT_VOLUME);
        int maxQueue = getConfiguration().getInt("music.max_queue_size", 100);
        int maxTrackDurationMs = getConfiguration().getInt("music.max_track_duration", 600_000);
        boolean enableSpotify = getConfiguration().getBoolean("providers.spotify.enabled", true);
        boolean enableSoundcloud = getConfiguration().getBoolean("providers.soundcloud.enabled", true);
        this.autoLeaveTimeoutMs = Math.max(0, getConfiguration().getInt("music.auto_leave_timeout", 300_000));
        int autoLeaveTimeoutMs = (int) this.autoLeaveTimeoutMs;

        // Playback-state persistence (resume after restart, e.g. Kubernetes pod rescheduling)
        this.persistenceEnabled = getConfiguration().getBoolean("music.persistence.enabled", true);
        int ttlSeconds = getConfiguration().getInt("music.persistence.ttl_seconds", 3600);
        this.persistenceTtlMillis = ttlSeconds <= 0 ? 0L : ttlSeconds * 1000L;

        logger.info("Music config loaded: vol={}, queue={}, maxTrackMs={}, spotify={}, soundcloud={}, autoLeaveMs={}, persistence={}, persistenceTtlS={}",
                defaultVolume, maxQueue, maxTrackDurationMs, enableSpotify, enableSoundcloud, autoLeaveTimeoutMs,
                persistenceEnabled, ttlSeconds);
    }

    /**
     * Whether playback-state persistence (save/restore across restarts) is enabled.
     */
    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * Maximum age of a persisted playback state before it is considered stale, in milliseconds.
     * A value of {@code 0} means the state never expires.
     */
    public long getPersistenceTtlMillis() {
        return persistenceTtlMillis;
    }

    /** How long the bot stays in the voice channel with nothing to play before leaving (`music.auto_leave_timeout`). */
    public long getAutoLeaveTimeoutMs() {
        return autoLeaveTimeoutMs;
    }

    // Getters
    public MusicManager getMusicManager() {
        return musicManager;
    }

    public PlaylistManager getPlaylistManager() {
        return playlistManager;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    // --- Autocomplete providers (called on JDA threads while the user types: in-memory only) ---

    private static final int SUGGESTION_LIMIT = 25;

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** /play: titles recently played in this server, matched against what is typed; value = the track URI. */
    private java.util.List<OptionChoice<String>> suggestRecentTracks(AutocompleteContext ctx) {
        if (ctx.guildId() == null) {
            return java.util.List.of();
        }
        String typed = ctx.partial().toLowerCase(java.util.Locale.ROOT);
        return musicManager.getRecentTracks(ctx.guildId()).entrySet().stream()
                .filter(e -> typed.isEmpty() || e.getKey().toLowerCase(java.util.Locale.ROOT).contains(typed))
                .limit(SUGGESTION_LIMIT)
                .map(e -> new OptionChoice<>(clip(e.getKey(), 100), e.getValue()))
                .toList();
    }

    /** /playlist name: the caller's playlists, or the server ones when {@code scope:server} is already typed. */
    private java.util.List<OptionChoice<String>> suggestPlaylistNames(AutocompleteContext ctx) {
        PlaylistScope scope = PlaylistScope.parse(ctx.options().get(OPTION_SCOPE), PlaylistScope.USER);
        String ownerId = scope == PlaylistScope.GUILD ? ctx.guildId() : ctx.userId();
        if (ownerId == null) {
            return java.util.List.of();
        }
        String typed = ctx.partial().toLowerCase(java.util.Locale.ROOT);
        return playlistManager.list(scope, ownerId).stream()
                .filter(playlist -> typed.isEmpty() || playlist.getName().toLowerCase(java.util.Locale.ROOT).contains(typed))
                .limit(SUGGESTION_LIMIT)
                .map(playlist -> new OptionChoice<>(clip(playlist.getName(), 100), playlist.getName()))
                .toList();
    }

    /** /remove: "1. Title", "2. Title"... from the queue, filtered by position or title. */
    private java.util.List<OptionChoice<Integer>> suggestQueuePositions(AutocompleteContext ctx) {
        if (ctx.guildId() == null) {
            return java.util.List.of();
        }
        String typed = ctx.partial().toLowerCase(java.util.Locale.ROOT);
        java.util.List<OptionChoice<Integer>> choices = new java.util.ArrayList<>();
        musicManager.findPlayer(ctx.guildId()).ifPresent(player -> {
            java.util.List<com.sedmelluq.discord.lavaplayer.track.AudioTrack> queue = player.getTrackScheduler().getQueue();
            for (int i = 0; i < queue.size() && choices.size() < SUGGESTION_LIMIT; i++) {
                String label = (i + 1) + ". " + queue.get(i).getInfo().title;
                if (typed.isEmpty() || label.toLowerCase(java.util.Locale.ROOT).contains(typed)) {
                    choices.add(new OptionChoice<>(clip(label, 100), i + 1));
                }
            }
        });
        return choices;
    }

    /** /queue: the existing pages. */
    private java.util.List<OptionChoice<Integer>> suggestQueuePages(AutocompleteContext ctx) {
        if (ctx.guildId() == null) {
            return java.util.List.of();
        }
        int size = musicManager.findPlayer(ctx.guildId()).map(p -> p.getTrackScheduler().getQueueSize()).orElse(0);
        int pages = Math.max(1, (size + 9) / 10);
        java.util.List<OptionChoice<Integer>> choices = new java.util.ArrayList<>();
        for (int page = 1; page <= Math.min(pages, SUGGESTION_LIMIT); page++) {
            if (ctx.partial().isEmpty() || String.valueOf(page).startsWith(ctx.partial())) {
                choices.add(new OptionChoice<>(page + " / " + pages, page));
            }
        }
        return choices;
    }

    /** /volume: the current level first, then the usual steps. */
    private java.util.List<OptionChoice<Integer>> suggestVolumes(AutocompleteContext ctx) {
        java.util.LinkedHashMap<Integer, String> levels = new java.util.LinkedHashMap<>();
        if (ctx.guildId() != null) {
            musicManager.findPlayer(ctx.guildId()).ifPresent(p -> levels.put(p.getVolume(), p.getVolume() + " (current)"));
        }
        for (int step : new int[]{100, 75, 50, 25, 10, 0}) {
            levels.putIfAbsent(step, String.valueOf(step));
        }
        return levels.entrySet().stream()
                .filter(e -> ctx.partial().isEmpty() || String.valueOf(e.getKey()).startsWith(ctx.partial()))
                .map(e -> new OptionChoice<>(e.getValue(), e.getKey()))
                .toList();
    }

    /** /seek: landmarks of the playing track (start, quarters, one minute before the end). */
    private java.util.List<OptionChoice<String>> suggestSeekPositions(AutocompleteContext ctx) {
        if (ctx.guildId() == null) {
            return java.util.List.of();
        }
        return musicManager.findPlayer(ctx.guildId())
                .map(p -> p.getPlayingTrack())
                .filter(track -> track != null && track.getDuration() > 0 && track.getDuration() != Long.MAX_VALUE)
                .map(track -> {
                    long duration = track.getDuration();
                    java.util.LinkedHashMap<String, String> marks = new java.util.LinkedHashMap<>();
                    marks.put(formatTime(0), formatTime(0));
                    for (int quarter = 1; quarter <= 3; quarter++) {
                        String t = formatTime(duration * quarter / 4);
                        marks.put(t + " (" + quarter * 25 + "%)", t);
                    }
                    if (duration > 60_000) {
                        String t = formatTime(duration - 60_000);
                        marks.put(t + " (-1:00)", t);
                    }
                    return marks.entrySet().stream()
                            .filter(e -> ctx.partial().isEmpty() || e.getValue().startsWith(ctx.partial()))
                            .map(e -> new OptionChoice<>(e.getKey(), e.getValue()))
                            .toList();
                })
                .orElse(java.util.List.of());
    }
}

// Internal simple permission implementation
class SimplePermission implements Permission {
    private final String name;
    private final String description;
    private final PermissionDefault def;

    public SimplePermission(String name, String description, PermissionDefault def) {
        this.name = name;
        this.description = description;
        this.def = def;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public PermissionDefault getDefault() {
        return def;
    }
}
