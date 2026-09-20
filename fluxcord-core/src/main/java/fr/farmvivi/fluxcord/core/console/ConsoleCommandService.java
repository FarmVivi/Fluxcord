package fr.farmvivi.fluxcord.core.console;

import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for handling console commands.
 * This service reads from standard input and processes commands without prefix.
 */
public class ConsoleCommandService {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleCommandService.class);

    private final CommandService commandService;
    private final InputStream input;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private JDA jda;

    /**
     * Creates a new console command service.
     *
     * @param commandService the command service to use for processing commands
     */
    public ConsoleCommandService(CommandService commandService) {
        this(commandService, System.in);
    }

    /** Reads commands from {@code input} instead of {@code System.in} (tests). */
    public ConsoleCommandService(CommandService commandService, InputStream input) {
        this.commandService = commandService;
        this.input = input;
    }

    /**
     * Sets the JDA instance.
     *
     * @param jda the JDA instance
     */
    public void setJDA(JDA jda) {
        this.jda = jda;
    }

    /**
     * Starts the console command service.
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ConsoleCommand-Thread");
            thread.setDaemon(true);
            return thread;
        });

        executorService.submit(this::handleConsoleInput);
        logger.info("Console command service started. Type 'help' for available commands or 'exit' to quit.");
    }

    /**
     * Stops the console command service.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        if (executorService != null) {
            executorService.shutdown();
        }

        logger.info("Console command service stopped");
    }

    /**
     * Handles console input in a loop.
     */
    private void handleConsoleInput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            while (running) {
                try {
                    String input = reader.readLine();

                    if (input == null) {
                        // EOF reached (Ctrl+D)
                        break;
                    }

                    input = input.trim();

                    if (input.isEmpty()) {
                        continue;
                    }

                    // Process as command
                    processConsoleCommand(input);
                } catch (Exception e) {
                    logger.error("Error reading console input", e);
                    // Continue running even if there's an error
                }
            }
        } catch (Exception e) {
            logger.error("Error in console command handler", e);
        }
    }

    /**
     * Processes a console command.
     *
     * @param input the console input
     */
    private void processConsoleCommand(String input) {
        if (jda == null) {
            System.out.println("[CONSOLE] Bot is not connected to Discord yet. Please wait...");
            return;
        }

        try {
            // Create console command event
            ConsoleCommandEvent event = new ConsoleCommandEvent(jda, input);

            // Process the command using the command service
            // Cast to SimpleCommandService to access processCommand method
            if (commandService instanceof SimpleCommandService simpleCommandService) {
                simpleCommandService.processCommand(event);
            } else {
                System.out.println("[CONSOLE] Console commands not supported with this command service implementation");
            }

        } catch (Exception e) {
            logger.error("Error processing console command: " + input, e);
            System.out.println("[CONSOLE] Error executing command: " + e.getMessage());
        }
    }

    /**
     * Checks if the console command service is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
}