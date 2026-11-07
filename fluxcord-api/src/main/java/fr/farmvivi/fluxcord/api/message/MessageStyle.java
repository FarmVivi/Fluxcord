package fr.farmvivi.fluxcord.api.message;

import fr.farmvivi.fluxcord.api.language.LanguageManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.Locale;

/**
 * The four shapes a message from the bot takes, so a failure looks like a failure wherever it comes from.
 *
 * <p>Extracted from {@code CommandMessageBuilder}, which still uses it and is where the colours and the
 * title keys were. They lived in the core's command package, so only a command reply could be one of these.
 * Everything else — a voice conversation dropping, a service with no voice to answer with — went out as a
 * line of prose, and a line of prose in a chat window reads as something the bot is <em>saying</em>. "The
 * live conversation failed: the realtime connection closed: 1007" looked like an odd remark rather than an
 * error, which is the confusion a red box exists to prevent.
 *
 * <p>The titles come from the core's own translations ({@code commands.titles.*}), so a plugin's error is
 * headed the same word, in the reader's language, as a command's.
 */
public enum MessageStyle {

    /** Neutral, for something the reader asked to see. */
    INFO(DiscordColor.DISCORD_BLURPLE, "commands.titles.info"),
    /** It worked. */
    SUCCESS(DiscordColor.DISCORD_GREEN, "commands.titles.success"),
    /** It worked, but something is worth knowing. */
    WARNING(DiscordColor.DISCORD_YELLOW, "commands.titles.warning"),
    /** It did not work. */
    ERROR(DiscordColor.DISCORD_RED, "commands.titles.error");

    private final DiscordColor color;
    private final String titleKey;

    MessageStyle(DiscordColor color, String titleKey) {
        this.color = color;
        this.titleKey = titleKey;
    }

    /** @return the bar Discord draws down the side of the embed */
    public DiscordColor getColor() {
        return color;
    }

    /** @return the translation key of the default title, in the core namespace */
    public String getTitleKey() {
        return titleKey;
    }

    /**
     * Builds an embed in this style.
     *
     * @param languages   where the default title is translated, or null to leave the title out
     * @param locale      the reader's locale, or null for the configured default
     * @param title       the title, or null to use this style's translated one
     * @param description the body, which may be null
     * @return the embed, ready to send
     */
    public MessageEmbed embed(LanguageManager languages, Locale locale, String title, String description) {
        EmbedBuilder embed = new EmbedBuilder().setColor(color.getColor());
        String heading = title == null || title.isEmpty() ? translate(languages, locale) : title;
        if (heading != null && !heading.isEmpty()) {
            embed.setTitle(heading);
        }
        if (description != null && !description.isEmpty()) {
            embed.setDescription(description);
        }
        return embed.build();
    }

    /**
     * Builds an embed with this style's translated title and nothing else to decide.
     *
     * @param languages   where the title is translated, or null to leave it out
     * @param description the body
     * @return the embed, ready to send
     */
    public MessageEmbed embed(LanguageManager languages, String description) {
        return embed(languages, null, null, description);
    }

    /**
     * @return the translated title, or null when there is nothing to ask. Null rather than the key itself:
     *         a box headed {@code commands.titles.error} is worse than one with no heading, and a raw key
     *         reaching a user is a bug this project has already shipped once.
     */
    private String translate(LanguageManager languages, Locale locale) {
        if (languages == null) {
            return null;
        }
        String translated = locale == null
                ? languages.getString(titleKey)
                : languages.getString(locale, titleKey);
        return titleKey.equals(translated) ? null : translated;
    }
}
