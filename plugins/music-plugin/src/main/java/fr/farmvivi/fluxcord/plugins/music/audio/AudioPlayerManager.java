package fr.farmvivi.fluxcord.plugins.music.audio;

import com.github.topi314.lavasrc.applemusic.AppleMusicSourceManager;
import com.github.topi314.lavasrc.deezer.DeezerAudioSourceManager;
import com.github.topi314.lavasrc.flowerytts.FloweryTTSSourceManager;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.*;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.*;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.source.SearchSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages audio players and sources for the music plugin.
 * Configures and registers all available audio sources.
 */
public class AudioPlayerManager {
    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerManager.class);

    private final MusicPlugin plugin;
    private final com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager playerManager;

    public AudioPlayerManager(MusicPlugin plugin) {
        this.plugin = plugin;
        this.playerManager = new DefaultAudioPlayerManager();
        initializeSources();
    }

    private void initializeSources() {
        logger.info("Initializing audio sources...");

        Configuration config = plugin.getConfiguration();

        // YouTube source with multiple clients for reliability
        boolean youtubeEnabled = config.getBoolean("providers.youtube.enabled", true);
        YoutubeAudioSourceManager youtubeSourceManager = null;
        if (youtubeEnabled) {
            logger.info("Enabling YouTube source provider");
            // Client order matters: clients are queried in the given order until one
            // succeeds. The first four are the official youtube-source recommended set
            // (https://github.com/lavalink-devs/youtube-source), in the recommended order:
            //   MUSIC       -> ytmsearch + music metadata
            //   ANDROID_VR  -> full feature parity with WEB, most reliable for playback
            //   WEB         -> full video/search/playlist/mix metadata & playback
            //   WEBEMBEDDED -> age-restricted fallback
            // The remaining clients are extra fallbacks, ordered by reliability.
            // ANDROID is intentionally omitted: it is heavily restricted and frequently
            // dysfunctional ("ANDROID is broken with no known fix").
            YoutubeSourceOptions options = new YoutubeSourceOptions().setAllowSearch(true);
            configureRemoteCipher(config, options);
            youtubeSourceManager = new YoutubeAudioSourceManager(options,
                    new MusicWithThumbnail(),
                    new AndroidVrWithThumbnail(),
                    // The only client that can use OAuth, and the reason the refresh token is worth having:
                    // it signs in, so it is answered when the others get LOGIN_REQUIRED. It carries no
                    // metadata at all ("Metadata Support: None"), so it is never what loads a track - it is
                    // skipped for search and lookup and only considered for playback. Placed after
                    // ANDROID_VR deliberately: when the address is not flagged, ANDROID_VR serves the
                    // stream without signing anybody in, and that is the cheaper path to keep.
                    new Tv(),
                    new WebWithThumbnail(),
                    new WebEmbeddedWithThumbnail(),
                    new MWebWithThumbnail(),
                    new IosWithThumbnail(),
                    new AndroidMusicWithThumbnail(),
                    new TvHtml5SimplyWithThumbnail()
            );
            configureYoutubeIdentity(config, youtubeSourceManager);
            playerManager.registerSourceManager(youtubeSourceManager);
        }

        // Spotify source
        boolean spotifyEnabled = config.getBoolean("providers.spotify.enabled", false);
        String spotifyClientId = config.getString("providers.spotify.client_id", null);
        String spotifyClientSecret = config.getString("providers.spotify.client_secret", null);
        String spotifyCountryCode = config.getString("providers.spotify.country_code", "US");
        if (spotifyEnabled && spotifyClientId != null && spotifyClientSecret != null) {
            logger.info("Enabling Spotify source provider");
            SpotifySourceManager spotifySourceManager = new SpotifySourceManager(
                    spotifyClientId,
                    spotifyClientSecret,
                    spotifyCountryCode,
                    playerManager,
                    new DefaultMirroringAudioTrackResolver(null)
            );
            playerManager.registerSourceManager(spotifySourceManager);
        }

        // Deezer source
        boolean deezerEnabled = config.getBoolean("providers.deezer.enabled", false);
        String deezerKey = config.getString("providers.deezer.master_decryption_key", null);
        String deezerARL = config.getString("providers.deezer.arl_cookie", null);
        if (deezerEnabled && deezerKey != null && deezerARL != null) {
            logger.info("Enabling Deezer source provider");
            DeezerAudioSourceManager deezerSourceManager = new DeezerAudioSourceManager(deezerKey, deezerARL);
            playerManager.registerSourceManager(deezerSourceManager);
        }

        // Apple Music source
        boolean appleMusicEnabled = config.getBoolean("providers.apple_music.enabled", false);
        String appleMusicToken = config.getString("providers.apple_music.token", null);
        String appleMusicCountryCode = config.getString("providers.apple_music.country_code", "US");
        if (appleMusicEnabled && appleMusicToken != null) {
            logger.info("Enabling Apple Music source provider");
            AppleMusicSourceManager appleMusicSourceManager = new AppleMusicSourceManager(
                    appleMusicToken,
                    appleMusicCountryCode,
                    playerManager,
                    new DefaultMirroringAudioTrackResolver(null)
            );
            playerManager.registerSourceManager(appleMusicSourceManager);
        }

        // Flowery TTS source
        boolean floweryEnabled = config.getBoolean("providers.flowery_tts.enabled", false);
        String floweryVoice = config.getString("providers.flowery_tts.voice", null);
        if (floweryEnabled && floweryVoice != null) {
            logger.info("Enabling Flowery TTS source provider");
            FloweryTTSSourceManager floweryTTSSourceManager = new FloweryTTSSourceManager(floweryVoice);
            playerManager.registerSourceManager(floweryTTSSourceManager);
        }

        // SoundCloud source
        boolean soundcloudEnabled = config.getBoolean("providers.soundcloud.enabled", false);
        if (soundcloudEnabled) {
            logger.info("Enabling SoundCloud source provider");
            SoundCloudDataReader dataReader = new DefaultSoundCloudDataReader();
            SoundCloudDataLoader dataLoader = new DefaultSoundCloudDataLoader();
            SoundCloudFormatHandler formatHandler = new DefaultSoundCloudFormatHandler();
            SoundCloudPlaylistLoader playlistLoader = new DefaultSoundCloudPlaylistLoader(
                    dataLoader, dataReader, formatHandler
            );
            SoundCloudAudioSourceManager soundCloudSourceManager = new SoundCloudAudioSourceManager(
                    true, dataReader, dataLoader, formatHandler, playlistLoader
            );
            playerManager.registerSourceManager(soundCloudSourceManager);
        }

        // Bandcamp source
        boolean bandcampEnabled = config.getBoolean("providers.bandcamp.enabled", false);
        if (bandcampEnabled) {
            logger.info("Enabling Bandcamp source provider");
            BandcampAudioSourceManager bandcampSourceManager = new BandcampAudioSourceManager();
            playerManager.registerSourceManager(bandcampSourceManager);
        }

        // Vimeo source
        boolean vimeoEnabled = config.getBoolean("providers.vimeo.enabled", false);
        if (vimeoEnabled) {
            logger.info("Enabling Vimeo source provider");
            VimeoAudioSourceManager vimeoSourceManager = new VimeoAudioSourceManager();
            playerManager.registerSourceManager(vimeoSourceManager);
        }

        // Twitch source
        boolean twitchEnabled = config.getBoolean("providers.twitch.enabled", false);
        if (twitchEnabled) {
            logger.info("Enabling Twitch source provider");
            TwitchStreamAudioSourceManager twitchSourceManager = new TwitchStreamAudioSourceManager();
            playerManager.registerSourceManager(twitchSourceManager);
        }

        // Getyarn source
        boolean getyarnEnabled = config.getBoolean("providers.getyarn.enabled", false);
        if (getyarnEnabled) {
            logger.info("Enabling Getyarn source provider");
            GetyarnAudioSourceManager getyarnSourceManager = new GetyarnAudioSourceManager();
            playerManager.registerSourceManager(getyarnSourceManager);
        }

        // HTTP source
        boolean httpEnabled = config.getBoolean("providers.http.enabled", false);
        if (httpEnabled) {
            logger.info("Enabling HTTP source provider");
            HttpAudioSourceManager httpSourceManager = new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY);
            playerManager.registerSourceManager(httpSourceManager);
        }

        // Local source
        boolean localEnabled = config.getBoolean("providers.local.enabled", false);
        if (localEnabled) {
            logger.info("Enabling Local source provider");
            LocalAudioSourceManager localSourceManager = new LocalAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY);
            playerManager.registerSourceManager(localSourceManager);
        }

        // Search source manager
        if (youtubeSourceManager != null) {
            logger.info("Enabling Search source provider");
            SearchSourceManager searchSourceManager = new SearchSourceManager(youtubeSourceManager, "ytsearch:");
            playerManager.registerSourceManager(searchSourceManager);
        } else {
            logger.warn("YouTube source manager is not initialized; Search source provider will have limited functionality.");
        }

        // Enable filter hot swap for effects
        playerManager.getConfiguration().setFilterHotSwapEnabled(true);

        logger.info("Audio sources initialized successfully");
    }

    /**
     * Hands signature deciphering to a server, when one is configured.
     *
     * <p>Upstream's own position since 2026-08-25: deciphering is "no longer managed within the source" and
     * a remote cipher server is what you ought to be using. YouTube changes the player script faster than
     * the extraction regexes can follow, and when it does, every client that needs a signature stops
     * working - which is the whole of what {@code Must find sig function from script} means.
     *
     * <p>Left empty, nothing changes and deciphering stays local. That is deliberate: a URL and a password
     * are a service somebody has to run, so this reads them rather than assuming them.
     */
    private void configureRemoteCipher(Configuration config, YoutubeSourceOptions options) {
        String url = trimmed(config.getString("providers.youtube.remote_cipher.url", ""));
        if (url.isEmpty()) {
            logger.info("YouTube: deciphering signatures locally"
                    + " (set providers.youtube.remote_cipher.url to use a cipher server)");
            return;
        }
        String password = trimmed(config.getString("providers.youtube.remote_cipher.password", ""));
        options.setRemoteCipher(url, password, "fluxcord");
        logger.info("YouTube: signatures will be deciphered by the server at {}", url);
    }

    /**
     * Tells YouTube who is asking, when we have been given something to say.
     *
     * <p>Without this, an address YouTube has flagged gets {@code LOGIN_REQUIRED} - "Sign in to confirm
     * you're not a bot" - and a player response carrying no stream formats whatsoever. Every client is then
     * tried in turn and the last one fails on the cipher, so the error that reaches the log names the cipher
     * and says nothing about the cause. MEASURED 2026-10-04 by replaying the plugin's own ANDROID_VR request
     * from the workstation: HTTP 200, {@code LOGIN_REQUIRED}, zero formats.
     *
     * <p>Two ways to answer, and upstream says not to combine them, so OAuth wins if both are filled in. A
     * refresh token is required for the OAuth path: the interactive flow prints a code and waits for somebody
     * to visit a URL, which an unattended bot cannot do, so it is never started from here.
     */
    private void configureYoutubeIdentity(Configuration config, YoutubeAudioSourceManager source) {
        String refreshToken = trimmed(config.getString("providers.youtube.oauth.refresh_token", ""));
        if (!refreshToken.isEmpty()) {
            source.useOauth2(refreshToken, true);
            logger.info("YouTube: authenticating with the configured OAuth refresh token");
            return;
        }
        if (config.getBoolean("providers.youtube.oauth.start_flow", false)) {
            // The one-time flow: the library prints a URL and a code, waits for somebody to approve it, and
            // then prints the refresh token. That is why it is behind its own switch rather than being the
            // fallback - a bot that starts this unasked would block on a prompt nobody is watching.
            logger.warn("YouTube: starting the OAuth flow. Watch this log for a code and a URL to open,"
                    + " then put the refresh token it prints into providers.youtube.oauth.refresh_token"
                    + " and set providers.youtube.oauth.start_flow back to false");
            source.useOauth2(null, false);
            return;
        }
        String poToken = trimmed(config.getString("providers.youtube.po_token.token", ""));
        String visitorData = trimmed(config.getString("providers.youtube.po_token.visitor_data", ""));
        if (!poToken.isEmpty() && !visitorData.isEmpty()) {
            // Static on the client class, not on the source manager: the token belongs to the WEB and
            // WEBEMBEDDED clients and to nothing else, which is also why it is not a complete answer.
            Web.setPoTokenAndVisitorData(poToken, visitorData);
            logger.info("YouTube: using the configured poToken for the WEB clients");
            return;
        }
        if (!poToken.isEmpty() || !visitorData.isEmpty()) {
            logger.warn("YouTube: a poToken needs both providers.youtube.po_token.token and"
                    + " .visitor_data; ignoring the one that was set");
        }
        logger.warn("YouTube: no OAuth refresh token and no poToken configured."
                + " A flagged address will be answered LOGIN_REQUIRED and playback will fail");
    }

    /** @return the value with no surrounding blanks, and "" for a missing one */
    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    public com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager getPlayerManager() {
        return playerManager;
    }

    public void shutdown() {
        logger.info("Shutting down audio player manager...");
        playerManager.shutdown();
    }
}