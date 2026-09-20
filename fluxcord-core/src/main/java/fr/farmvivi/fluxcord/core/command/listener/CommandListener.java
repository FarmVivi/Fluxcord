package fr.farmvivi.fluxcord.core.command.listener;

import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDA event listener for commands.
 * This listener processes slash commands and text commands and passes them to the command service.
 */
public class CommandListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandListener.class);

    private final SimpleCommandService commandService;

    /**
     * Creates a new command listener.
     *
     * @param commandService the command service
     */
    public CommandListener(SimpleCommandService commandService) {
        this.commandService = commandService;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        // Pass the event to the command service
        commandService.processCommand(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        commandService.handleAutocomplete(event);
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        // Ignore bots
        if (event.getAuthor().isBot()) {
            return;
        }

        // Pass the event to the command service
        commandService.processCommand(event);
    }
}
