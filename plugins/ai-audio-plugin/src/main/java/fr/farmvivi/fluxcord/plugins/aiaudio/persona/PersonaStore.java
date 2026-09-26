package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the persona overrides and the moods live.
 *
 * <p>Three levels, narrowest last: the configured base, then a per-server override, then a per-channel one.
 * An override states only what differs, so a server can set a language and a single channel a tone without
 * either repeating the rest.
 *
 * <p>Moods are per channel, because a mood belongs to a conversation rather than to a community — the
 * chatter in one voice channel should not make the bot terse in another.
 *
 * <p>Everything is stored as a record whose fields are all strings or numbers ({@link StoredPersona}). It
 * would be shorter to store {@link Persona} directly, but its {@code Locale} and {@code List<String>} are
 * exactly the shapes that come back from Gson as something else; converting explicitly is two methods and no
 * surprises.
 */
public class PersonaStore {

    /** Key of the server-wide override, in the guild scope. */
    static final String GUILD_KEY = "persona";
    /** Prefix of a channel override, in the guild scope. */
    static final String CHANNEL_PREFIX = "persona.";
    /** Prefix of a channel's mood, in the guild scope. */
    static final String MOOD_PREFIX = "mood.";

    private static final Logger logger = LoggerFactory.getLogger(PersonaStore.class);

    private final PluginDataStorageAdapter storage;
    private final Persona base;

    /**
     * @param storage the plugin's namespaced storage
     * @param base    the configured persona, used wherever nothing overrides it
     */
    public PersonaStore(PluginDataStorageAdapter storage, Persona base) {
        this.storage = storage;
        this.base = base;
    }

    /** @return the configured persona, before any override */
    public Persona base() {
        return base;
    }

    /**
     * The persona that applies in one place.
     *
     * @param guildId   the server, or null outside one
     * @param channelId the channel, or null when it does not matter
     * @return the base, overridden by the server and then by the channel
     */
    public Persona effective(String guildId, String channelId) {
        if (guildId == null) {
            return base;
        }
        Persona result = base.overriddenBy(read(scope(guildId), GUILD_KEY));
        if (channelId != null) {
            result = result.overriddenBy(read(scope(guildId), CHANNEL_PREFIX + channelId));
        }
        return result;
    }

    /** @return the server's own override, empty when it has none */
    public Optional<Persona> guildOverride(String guildId) {
        return Optional.ofNullable(read(scope(guildId), GUILD_KEY));
    }

    /** @return the channel's own override, empty when it has none */
    public Optional<Persona> channelOverride(String guildId, String channelId) {
        return Optional.ofNullable(read(scope(guildId), CHANNEL_PREFIX + channelId));
    }

    /**
     * Stores a server-wide override.
     *
     * @param guildId the server
     * @param persona what differs from the base
     */
    public void setGuildPersona(String guildId, Persona persona) {
        write(scope(guildId), GUILD_KEY, persona);
    }

    /**
     * Stores a channel override.
     *
     * @param guildId   the server
     * @param channelId the channel
     * @param persona   what differs from the server's persona
     */
    public void setChannelPersona(String guildId, String channelId, Persona persona) {
        write(scope(guildId), CHANNEL_PREFIX + channelId, persona);
    }

    /**
     * Drops a server's override, so the base applies again.
     *
     * @return true when there was one
     */
    public boolean resetGuild(String guildId) {
        return scope(guildId).remove(GUILD_KEY);
    }

    /**
     * Drops a channel's override, so the server's persona applies again.
     *
     * @return true when there was one
     */
    public boolean resetChannel(String guildId, String channelId) {
        return scope(guildId).remove(CHANNEL_PREFIX + channelId);
    }

    /**
     * The mood of one channel, faded to what it is now.
     *
     * @param guildId   the server
     * @param channelId the channel
     * @param nowMs     the current time in milliseconds
     * @return the mood, neutral when the channel has none
     */
    public Mood mood(String guildId, String channelId, long nowMs) {
        if (guildId == null || channelId == null) {
            return Mood.neutral(nowMs);
        }
        return scope(guildId).get(MOOD_PREFIX + channelId, Mood.class)
                .map(mood -> mood.at(nowMs))
                .orElseGet(() -> Mood.neutral(nowMs));
    }

    /**
     * Moves a channel's mood and stores it.
     *
     * @param guildId     the server
     * @param channelId   the channel
     * @param energyDelta how much more excited or calm
     * @param warmthDelta how much friendlier or colder
     * @param nowMs       the current time in milliseconds
     * @return the mood as stored
     */
    public Mood nudgeMood(String guildId, String channelId, double energyDelta, double warmthDelta, long nowMs) {
        if (guildId == null || channelId == null) {
            return Mood.neutral(nowMs);
        }
        Mood moved = stored(guildId, channelId).orElseGet(() -> Mood.neutral(nowMs))
                .nudged(energyDelta, warmthDelta, nowMs);
        try {
            scope(guildId).set(MOOD_PREFIX + channelId, moved);
        } catch (RuntimeException e) {
            // A mood is not worth failing a conversation over.
            logger.warn("Could not store the mood of channel {}: {}", channelId, e.getMessage());
        }
        return moved;
    }

    /**
     * Forgets a channel's mood.
     *
     * @return true when there was one
     */
    public boolean resetMood(String guildId, String channelId) {
        return scope(guildId).remove(MOOD_PREFIX + channelId);
    }

    /** The stored mood, without decay — only {@link #nudgeMood} wants it raw. */
    private Optional<Mood> stored(String guildId, String channelId) {
        return scope(guildId).get(MOOD_PREFIX + channelId, Mood.class);
    }

    private ScopedStorage scope(String guildId) {
        return storage.getGuildStorage(guildId);
    }

    private Persona read(ScopedStorage scope, String key) {
        try {
            return scope.get(key, StoredPersona.class).map(StoredPersona::toPersona).orElse(null);
        } catch (RuntimeException e) {
            logger.warn("Could not read the persona at '{}': {}", key, e.getMessage());
            return null;
        }
    }

    private void write(ScopedStorage scope, String key, Persona persona) {
        try {
            scope.set(key, StoredPersona.from(persona));
        } catch (RuntimeException e) {
            logger.warn("Could not store the persona at '{}': {}", key, e.getMessage());
        }
    }

    /**
     * The stored shape of a persona: strings only.
     *
     * @param name         the display name, may be empty in an override
     * @param traits       comma-separated, may be empty
     * @param tone         may be empty
     * @param languageTag  a BCP 47 tag, may be empty
     * @param instructions may be empty
     */
    record StoredPersona(String name, String traits, String tone, String languageTag, String instructions) {

        static StoredPersona from(Persona persona) {
            return new StoredPersona(persona.name(), String.join(",", persona.traits()), persona.tone(),
                    persona.language() == null ? "" : persona.language().toLanguageTag(), persona.instructions());
        }

        Persona toPersona() {
            List<String> parsed = traits == null || traits.isBlank()
                    ? List.of()
                    : Arrays.stream(traits.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
            // An override leaves the fields it does not change blank, and Persona treats blank as "keep",
            // so a null language must stay null rather than becoming a default here.
            Locale locale = languageTag == null || languageTag.isBlank() ? null : Locale.forLanguageTag(languageTag);
            return new Persona(name == null ? "" : name, parsed, tone == null ? "" : tone, locale,
                    instructions == null ? "" : instructions);
        }
    }
}
