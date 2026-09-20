package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.reply.ReplyTarget;
import fr.farmvivi.fluxcord.core.util.DiscordColor;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Collection;
import java.util.Locale;

/**
 * Composes a command reply — content, typed embeds ({@link #info}, {@link #success}, {@link #warning},
 * {@link #error} with translated titles), components — within Discord's limits, then hands it to the
 * {@link ReplyTarget} of the event it answers. Transport differences (interaction deferral and placeholder
 * edits, ephemeral emulation on text, console rendering) live in the targets.
 */
public class CommandMessageBuilder extends MessageCreateBuilder {
    private final ReplyTarget target;
    private final LanguageManager languageManager;
    private final Locale locale;
    private boolean differ;
    private boolean ephemeral;

    /**
     * @param event           the JDA event that triggered the command (decides the transport)
     * @param languageManager translations for the default embed titles
     * @param locale          the locale of those titles
     */
    public CommandMessageBuilder(Event event, LanguageManager languageManager, Locale locale) {
        this(ReplyTarget.of(event), languageManager, locale);
    }

    public CommandMessageBuilder(ReplyTarget target, LanguageManager languageManager, Locale locale) {
        this.target = target;
        this.languageManager = languageManager;
        this.locale = locale;
    }

    /** Replaces everything composed so far with this content (truncated to Discord's limit). */
    @NotNull
    @Override
    public CommandMessageBuilder setContent(String content) {
        clear();
        if (content != null && !content.isEmpty()) {
            super.setContent(truncate(content, Message.MAX_CONTENT_LENGTH));
        }
        return this;
    }

    @NotNull
    @Override
    public CommandMessageBuilder setComponents(@NotNull Collection<? extends MessageTopLevelComponent> components) {
        this.components.clear();
        addComponents(components);
        return this;
    }

    @NotNull
    @Override
    public CommandMessageBuilder setEmbeds(@NotNull Collection<? extends MessageEmbed> embeds) {
        this.embeds.clear();
        addEmbeds(embeds);
        return this;
    }

    @NotNull
    @Override
    public CommandMessageBuilder addContent(@NotNull String content) {
        super.addContent(truncate(content, Message.MAX_CONTENT_LENGTH));
        return this;
    }

    @NotNull
    @Override
    public CommandMessageBuilder addEmbeds(@NotNull Collection<? extends MessageEmbed> embeds) {
        super.addEmbeds(embeds.stream().limit(Math.max(0, Message.MAX_EMBED_COUNT - this.embeds.size())).toList());
        return this;
    }

    @NotNull
    @Override
    public CommandMessageBuilder addComponents(@NotNull Collection<? extends MessageTopLevelComponent> components) {
        super.addComponents(components.stream().limit(Math.max(0, Message.MAX_COMPONENT_COUNT - this.components.size())).toList());
        return this;
    }

    /** Whether {@link #replyNow()} should only defer a fresh interaction (see {@link ReplyTarget#send}). */
    public boolean isDiffer() {
        return differ;
    }

    public void setDiffer(boolean differ) {
        this.differ = differ;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    /** Sends what was composed to the transport of the triggering event. */
    public void replyNow() {
        target.send(isEmpty() ? null : build(), ephemeral, differ);
        differ = false;
    }

    // ---- typed embeds ------------------------------------------------------------------------------------------

    public void info(String description) {
        info(null, description);
    }

    public void info(String title, String description) {
        addTyped(DiscordColor.DISCORD_BLURPLE.getColor(), "commands.titles.info", title, description);
    }

    public void success(String description) {
        success(null, description);
    }

    public void success(String title, String description) {
        addTyped(DiscordColor.DISCORD_GREEN.getColor(), "commands.titles.success", title, description);
    }

    public void warning(String description) {
        warning(null, description);
    }

    public void warning(String title, String description) {
        addTyped(DiscordColor.DISCORD_YELLOW.getColor(), "commands.titles.warning", title, description);
    }

    public void error(String description) {
        error(null, description);
    }

    public void error(String title, String description) {
        addTyped(DiscordColor.DISCORD_RED.getColor(), "commands.titles.error", title, description);
    }

    private void addTyped(Color color, String defaultTitleKey, String title, String description) {
        EmbedBuilder embed = new EmbedBuilder().setColor(color)
                .setTitle(title == null || title.isEmpty() ? languageManager.getString(locale, defaultTitleKey) : title);
        if (description != null && !description.isEmpty()) {
            embed.setDescription(description);
        }
        addEmbeds(embed.build());
    }

    private static String truncate(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() > maxLength ? content.substring(0, maxLength - 3) + "..." : content;
    }
}
