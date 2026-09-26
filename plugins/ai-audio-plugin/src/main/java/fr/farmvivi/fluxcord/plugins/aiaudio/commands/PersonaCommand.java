package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Mood;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /persona <show|set|reset> [field] [value] [scope]} — inspect and adjust who the bot is here.
 *
 * <p>Set one field at a time, on the server or on the voice channel the bot is in. An override only states
 * what differs, so setting a tone on one channel leaves its name, traits and language to the server, and
 * those to the configuration.
 */
public class PersonaCommand extends AiAudioCommand {

    /** The {@code action} option values. */
    public static final String SHOW = "show";
    public static final String SET = "set";
    public static final String RESET = "reset";

    /** The {@code field} option values. */
    public static final String FIELD_NAME = "name";
    public static final String FIELD_TRAITS = "traits";
    public static final String FIELD_TONE = "tone";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_INSTRUCTIONS = "instructions";
    public static final String FIELD_MOOD = "mood";

    /** The {@code scope} option values. */
    public static final String SCOPE_SERVER = "server";
    public static final String SCOPE_CHANNEL = "channel";

    public PersonaCommand(AIAudioPlugin plugin) {
        super(plugin);
    }

    /**
     * @param ctx    the command context
     * @param action {@link #SHOW}, {@link #SET} or {@link #RESET}
     * @param field  which field to set or reset, ignored by {@code show}
     * @param value  the new value, for {@code set}
     * @param scope  {@link #SCOPE_SERVER} or {@link #SCOPE_CHANNEL}
     */
    public void execute(CommandContext ctx, String action, String field, String value, String scope) {
        Optional<Guild> optGuild = guild(ctx);
        if (optGuild.isEmpty()) {
            return;
        }
        Guild guild = optGuild.get();
        boolean channelScope = SCOPE_CHANNEL.equalsIgnoreCase(scope);
        String channelId = connectedChannelId(guild);

        if (channelScope && channelId == null) {
            ctx.replyError(text(ctx, "errors.not_connected"));
            return;
        }

        switch (action == null ? SHOW : action.toLowerCase(Locale.ROOT)) {
            case SET -> set(ctx, guild, channelScope, channelId, field, value);
            case RESET -> reset(ctx, guild, channelScope, channelId, field);
            default -> show(ctx, guild, channelId);
        }
    }

    private void show(CommandContext ctx, Guild guild, String channelId) {
        PersonaStore store = plugin.getPersonaStore();
        Persona persona = store.effective(guild.getId(), channelId);
        Mood mood = store.mood(guild.getId(), channelId, System.currentTimeMillis());

        ctx.replyInfo(text(ctx, "messages.persona_shown",
                persona.name(),
                persona.traitsAsText(),
                persona.tone(),
                persona.language().toLanguageTag(),
                persona.instructions().isEmpty() ? "-" : persona.instructions(),
                text(ctx, "mood." + mood.label())));
    }

    private void set(CommandContext ctx, Guild guild, boolean channelScope, String channelId,
                     String field, String value) {
        if (!isOperator(ctx)) {
            ctx.replyError(text(ctx, "errors.no_permission"));
            return;
        }
        if (field == null || value == null || value.isBlank()) {
            ctx.replyError(text(ctx, "errors.persona_usage"));
            return;
        }
        PersonaStore store = plugin.getPersonaStore();
        Persona current = channelScope
                ? store.channelOverride(guild.getId(), channelId).orElse(Persona.nothing())
                : store.guildOverride(guild.getId()).orElse(Persona.nothing());

        Persona updated = withField(current, field.toLowerCase(Locale.ROOT), value.strip());
        if (updated == null) {
            ctx.replyError(text(ctx, "errors.persona_unknown_field", field));
            return;
        }
        if (channelScope) {
            store.setChannelPersona(guild.getId(), channelId, updated);
        } else {
            store.setGuildPersona(guild.getId(), updated);
        }
        ctx.replySuccess(text(ctx, "messages.persona_set", field, value.strip(),
                text(ctx, channelScope ? "messages.scope_channel" : "messages.scope_server")));
    }

    private void reset(CommandContext ctx, Guild guild, boolean channelScope, String channelId, String field) {
        if (!isOperator(ctx)) {
            ctx.replyError(text(ctx, "errors.no_permission"));
            return;
        }
        PersonaStore store = plugin.getPersonaStore();
        if (FIELD_MOOD.equalsIgnoreCase(field)) {
            // Resetting the mood is the one reset that is not about an override: it clears a feeling.
            store.resetMood(guild.getId(), channelId == null ? "" : channelId);
            ctx.replySuccess(text(ctx, "messages.mood_reset"));
            return;
        }
        boolean had = channelScope
                ? store.resetChannel(guild.getId(), channelId)
                : store.resetGuild(guild.getId());
        ctx.replySuccess(text(ctx, had ? "messages.persona_reset" : "messages.persona_nothing_to_reset",
                text(ctx, channelScope ? "messages.scope_channel" : "messages.scope_server")));
    }

    /**
     * A copy of {@code persona} with one field replaced.
     *
     * @return the updated persona, or null when the field is not one of ours
     */
    private Persona withField(Persona persona, String field, String value) {
        return switch (field) {
            case FIELD_NAME -> new Persona(value, persona.traits(), persona.tone(), persona.language(),
                    persona.instructions());
            case FIELD_TRAITS -> new Persona(persona.name(), splitTraits(value), persona.tone(),
                    persona.language(), persona.instructions());
            case FIELD_TONE -> new Persona(persona.name(), persona.traits(), value, persona.language(),
                    persona.instructions());
            case FIELD_LANGUAGE -> language(persona, value);
            case FIELD_INSTRUCTIONS -> new Persona(persona.name(), persona.traits(), persona.tone(),
                    persona.language(), value);
            default -> null;
        };
    }

    /** A tag Java cannot read would silently become a persona that answers in the wrong language. */
    private Persona language(Persona persona, String value) {
        Locale locale = Locale.forLanguageTag(value);
        if (locale.getLanguage().isEmpty()) {
            return null;
        }
        return new Persona(persona.name(), persona.traits(), persona.tone(), locale, persona.instructions());
    }

    private List<String> splitTraits(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::strip)
                .filter(trait -> !trait.isEmpty()).toList();
    }

    /** Changing who the bot is for everyone is an administrative action. */
    private boolean isOperator(CommandContext ctx) {
        return plugin.getPermissions().hasPermission(ctx.getUser().getId(),
                plugin.permissionKey(AIAudioPlugin.PERM_ADMIN));
    }

    private String connectedChannelId(Guild guild) {
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        return channel == null ? null : channel.getId();
    }
}
