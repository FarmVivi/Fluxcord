package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.util.DiscordColor;
import net.dv8tion.jda.api.EmbedBuilder;

/**
 * System command that shuts down the bot.
 * This command is only available to administrators.
 */
public class ShutdownCommand {

    private final Command command;
    private final LanguageManager languageManager;

    /**
     * Creates a new shutdown command with a language manager.
     *
     * @param languageManager the language manager for translations
     */
    private final PermissionManager permissionManager;
    private final Runnable shutdownHandler;

    /**
     * @param shutdownHandler what actually stops the bot (the runtime's {@code requestShutdown}); called from a
     *                        daemon thread one second after the reply, so it must return quickly
     */
    public ShutdownCommand(LanguageManager languageManager, PermissionManager permissionManager, Runnable shutdownHandler) {
        this.languageManager = languageManager;
        this.permissionManager = permissionManager;
        this.shutdownHandler = shutdownHandler;

        command = new SimpleCommandBuilder()
                .name("shutdown")
                .description("Shuts down the bot")
                .category("System")
                .aliases("stop", "exit", "quit")
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
     * Executes the shutdown command.
     *
     * @param context the command context
     * @param command the command
     * @return the command result
     */
    private CommandResult execute(CommandContext context, Command command) {
        // Console has no user; from Discord only an operator may stop the bot (permission default OP semantics)
        if (context.getUser() != null) {
            String guildId = context.getGuild().map(g -> g.getId()).orElse(null);
            if (!permissionManager.isOperator(context.getUser().getId(), guildId)) {
                context.setEphemeral(true);
                context.replyError(languageManager.getString(context.getLocale(), "commands.perm.not_operator"));
                return CommandResult.error("not an operator");
            }
        }
        // Réponses administratives en éphémère
        context.setEphemeral(true);

        String shutdownMessage = languageManager.getString(context.getLocale(), "commands.shutdown.shutting_down");

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(DiscordColor.DISCORD_BLURPLE.getColor())
                .setTitle(languageManager.getString(context.getLocale(), "commands.titles.info"))
                .setDescription(shutdownMessage);

        context.replyEmbed(embed);

        // Let the reply reach Discord, then hand the shutdown to the runtime (FluxcordRuntime.requestShutdown)
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            shutdownHandler.run();
        }, "Shutdown-Request");
        shutdownThread.setDaemon(true);
        shutdownThread.start();

        return CommandResult.success();
    }
}
