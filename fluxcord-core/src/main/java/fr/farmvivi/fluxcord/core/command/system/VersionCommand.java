package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.Fluxcord;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.util.DiscordColor;
import net.dv8tion.jda.api.EmbedBuilder;

/**
 * System command that displays version information about the bot.
 */
public class VersionCommand {

    private final Command command;
    private final LanguageManager languageManager;

    /**
     * Creates a new version command with a language manager.
     *
     * @param languageManager the language manager for translations
     */
    public VersionCommand(LanguageManager languageManager) {
        this.languageManager = languageManager;

        command = new SimpleCommandBuilder()
                .name("version")
                .description("Shows the bot version")
                .category("System")
                // Answers whoever asked, and only them: none of this is news to the channel.
                .ephemeral(true)
                .aliases("ver", "v", "about")
                .executor(this::execute)
                .build();
    }

    /**
     * Gets the command instance.
     *
     * @return the command
     */
    public Command getCommand() {
        return command;
    }

    /**
     * Executes the version command.
     *
     * @param context the command context
     * @param command the command
     * @return the command result
     */
    private CommandResult execute(CommandContext context, Command command) {
        // Set ephemeral for version responses as they are typically informational
        context.setEphemeral(true);

        EmbedBuilder embed = new EmbedBuilder().setColor(DiscordColor.DISCORD_BLURPLE.getColor());

        embed.setTitle(languageManager.getString(context.getLocale(), "commands.version.title"))
                .addField(languageManager.getString(context.getLocale(), "commands.version.name"), Fluxcord.NAME, true)
                .addField(languageManager.getString(context.getLocale(), "commands.version.version"), Fluxcord.VERSION, true)
                .addField(languageManager.getString(context.getLocale(), "commands.version.mode"),
                        Fluxcord.PRODUCTION
                                ? languageManager.getString(context.getLocale(), "commands.version.production")
                                : languageManager.getString(context.getLocale(), "commands.version.development"),
                        true)
                .addField(languageManager.getString(context.getLocale(), "commands.version.java_version"), System.getProperty("java.version"), true)
                .addField(languageManager.getString(context.getLocale(), "commands.version.jvm"),
                        System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"), true)
                .addField(languageManager.getString(context.getLocale(), "commands.version.os"),
                        System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")", true);

        if (context.getJDA() != null) {
            embed.addField(languageManager.getString(context.getLocale(), "commands.version.gateway_ping"),
                    context.getJDA().getGatewayPing() + "ms", true);

            if (context.getJDA().getShardInfo() != null) {
                embed.addField(languageManager.getString(context.getLocale(), "commands.version.shard"),
                        context.getJDA().getShardInfo().getShardId() + "/" + context.getJDA().getShardInfo().getShardTotal(), true);
            }
        }

        context.replyEmbed(embed);
        return CommandResult.success();
    }
}
