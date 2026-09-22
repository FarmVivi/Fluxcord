package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist.PlaylistTrack;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistManager;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistManager.SaveResult;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code playlist <save|load|list|show|delete> [name] [personal|server]}: named playlists of the
 * current queue.
 *
 * <p>A personal playlist belongs to the caller and follows them across servers; a server playlist is
 * shared by everyone on the guild and may only be created or deleted by someone holding the
 * {@code admin} permission of this plugin.
 */
public class PlaylistCommand extends MusicCommand {
    private static final int LISTED_TRACKS = 15;

    public PlaylistCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, String action, String name, String scopeValue) {
        Guild guild = guild(ctx).orElse(null);
        if (guild == null) {
            return;
        }
        User user = ctx.getUser();
        if (user == null) {
            // Console: there is no caller to own a personal playlist and no channel to play in.
            ctx.replyError(text(ctx, "music.playlist.discord_only"));
            return;
        }

        PlaylistScope scope = PlaylistScope.parse(scopeValue, PlaylistScope.USER);
        String ownerId = scope == PlaylistScope.GUILD ? guild.getId() : user.getId();

        switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
            case "list" -> list(ctx, scope, ownerId);
            case "show" -> show(ctx, scope, ownerId, name);
            case "load" -> load(ctx, scope, ownerId, name);
            case "save" -> {
                if (mayWrite(ctx, scope, guild, user)) {
                    save(ctx, guild, scope, ownerId, name);
                }
            }
            case "delete" -> {
                if (mayWrite(ctx, scope, guild, user)) {
                    delete(ctx, scope, ownerId, name);
                }
            }
            default -> ctx.replyError(text(ctx, "music.playlist.usage"));
        }
    }

    /** Server playlists are shared, so creating and deleting them needs the plugin's admin permission. */
    private boolean mayWrite(CommandContext ctx, PlaylistScope scope, Guild guild, User user) {
        if (scope != PlaylistScope.GUILD
                || plugin.getPermissions().hasPermission(user.getId(), guild.getId(), plugin.permissionKey("admin"))) {
            return true;
        }
        ctx.replyError(text(ctx, "music.playlist.admin_only"));
        return false;
    }

    private void save(CommandContext ctx, Guild guild, PlaylistScope scope, String ownerId, String name) {
        PlaylistManager playlists = plugin.getPlaylistManager();
        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        List<PlaylistTrack> tracks = new ArrayList<>();
        AudioTrack current = player.getPlayingTrack();
        if (current != null) {
            tracks.add(toEntry(current));
        }
        player.getTrackScheduler().getQueue().forEach(track -> tracks.add(toEntry(track)));

        switch (playlists.save(scope, ownerId, name, tracks)) {
            case CREATED -> ctx.replySuccess(text(ctx, "music.playlist.saved", name, tracks.size()));
            case REPLACED -> ctx.replySuccess(text(ctx, "music.playlist.replaced", name, tracks.size()));
            case INVALID_NAME -> ctx.replyError(text(ctx, "music.playlist.invalid_name",
                    PlaylistManager.MAX_NAME_LENGTH));
            case NO_TRACKS -> ctx.replyError(text(ctx, "music.playlist.nothing_to_save"));
            case TOO_MANY_PLAYLISTS -> ctx.replyError(text(ctx, "music.playlist.too_many",
                    playlists.getMaxPlaylists(scope)));
            case TOO_MANY_TRACKS -> ctx.replyError(text(ctx, "music.playlist.too_many_tracks",
                    playlists.getMaxTracks()));
        }
    }

    private void load(CommandContext ctx, PlaylistScope scope, String ownerId, String name) {
        Optional<Playlist> playlist = plugin.getPlaylistManager().find(scope, ownerId, name);
        if (playlist.isEmpty()) {
            replyNotFound(ctx, name);
            return;
        }
        plugin.getMusicManager().loadPlaylist(ctx, playlist.get());
    }

    private void delete(CommandContext ctx, PlaylistScope scope, String ownerId, String name) {
        if (plugin.getPlaylistManager().delete(scope, ownerId, name)) {
            ctx.replySuccess(text(ctx, "music.playlist.deleted", name));
        } else {
            replyNotFound(ctx, name);
        }
    }

    private void list(CommandContext ctx, PlaylistScope scope, String ownerId) {
        List<Playlist> playlists = plugin.getPlaylistManager().list(scope, ownerId);

        if (playlists.isEmpty()) {
            ctx.replyInfo(text(ctx, "music.playlist.none"));
            return;
        }

        StringBuilder body = new StringBuilder();
        for (Playlist playlist : playlists) {
            body.append(text(ctx, "music.playlist.list_entry",
                            playlist.getName(), playlist.getTrackCount(), TimeParser.formatTimeShort(playlist.getTotalDuration())))
                    .append('\n');
        }

        ctx.replyEmbed(new EmbedBuilder()
                .setColor(Color.BLUE)
                .setTitle(text(ctx, "music.playlist.list_title",
                        playlists.size(), text(ctx, scopeKey(scope))))
                .setDescription(body.toString()));
    }

    private void show(CommandContext ctx, PlaylistScope scope, String ownerId, String name) {
        Optional<Playlist> optPlaylist = plugin.getPlaylistManager().find(scope, ownerId, name);
        if (optPlaylist.isEmpty()) {
            replyNotFound(ctx, name);
            return;
        }
        Playlist playlist = optPlaylist.get();

        StringBuilder body = new StringBuilder();
        List<PlaylistTrack> tracks = playlist.getTracks();
        for (int i = 0; i < Math.min(tracks.size(), LISTED_TRACKS); i++) {
            PlaylistTrack track = tracks.get(i);
            body.append(String.format("%d. [%s](%s) - %s%n",
                    i + 1, track.title(), track.url(), TimeParser.formatTime(track.duration())));
        }
        if (tracks.size() > LISTED_TRACKS) {
            body.append(text(ctx, "music.playlist.and_more", tracks.size() - LISTED_TRACKS));
        }

        ctx.replyEmbed(new EmbedBuilder()
                .setColor(Color.BLUE)
                .setTitle(playlist.getName())
                .setDescription(body.toString())
                .addField(text(ctx, "music.tracks"), String.valueOf(tracks.size()), true)
                .addField(text(ctx, "music.queue.duration"),
                        TimeParser.formatTimeShort(playlist.getTotalDuration()), true));
    }

    private void replyNotFound(CommandContext ctx, String name) {
        ctx.replyError(text(ctx, "music.playlist.not_found", String.valueOf(name)));
    }

    private static String scopeKey(PlaylistScope scope) {
        return scope == PlaylistScope.GUILD ? "music.playlist.scope.server" : "music.playlist.scope.personal";
    }

    private static PlaylistTrack toEntry(AudioTrack track) {
        return new PlaylistTrack(track.getInfo().uri, track.getInfo().title, track.getInfo().author,
                track.getDuration());
    }
}
