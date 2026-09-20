package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.*;
import fr.farmvivi.fluxcord.api.command.event.CommandExecuteEvent;
import fr.farmvivi.fluxcord.api.command.event.CommandExecutedEvent;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.exception.CommandPermissionException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.command.option.OptionType2;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.GuildStorage;
import fr.farmvivi.fluxcord.core.command.listener.CommandListener;
import fr.farmvivi.fluxcord.core.command.parser.CommandParser;
import fr.farmvivi.fluxcord.core.command.parser.ConsoleCommandParser;
import fr.farmvivi.fluxcord.core.command.parser.SlashCommandParser;
import fr.farmvivi.fluxcord.core.command.parser.TextCommandParser;
import fr.farmvivi.fluxcord.core.command.system.HelpCommand;
import fr.farmvivi.fluxcord.core.command.system.PermCommand;
import fr.farmvivi.fluxcord.core.command.system.ShutdownCommand;
import fr.farmvivi.fluxcord.core.command.system.VersionCommand;
import fr.farmvivi.fluxcord.core.util.Debouncer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.FileType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Implementation of the CommandService interface.
 * This service manages the lifecycle of commands, including registration,
 * execution, and synchronization with Discord.
 */
public class SimpleCommandService implements CommandService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleCommandService.class);
    private static final long SYNC_DELAY_MS = 1000; // 1 second delay
    private final CommandRegistry registry;
    private final List<CommandParser> parsers = new ArrayList<>();
    private final EventManager eventManager;
    private final LanguageManager languageManager;
    private final PermissionManager permissionManager;
    private final Configuration configuration;
    private final DataStorageManager storageManager;
    // Statistics
    private final AtomicLong commandExecutionCount = new AtomicLong();
    private final AtomicLong successfulCommandExecutionCount = new AtomicLong();
    private final AtomicLong failedCommandExecutionCount = new AtomicLong();
    private final AtomicLong totalExecutionTimeNs = new AtomicLong();
    // Cooldowns: userId -> (commandName -> expirationTime)
    private final Map<String, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private JDA jda;
    private boolean enabled;
    private String defaultPrefix;
    private CommandListener commandListener;
    private boolean systemCommandsRegistered = false;
    private boolean duringInitialization = false;
    // Debounced synchronization using existing Debouncer utility
    private Debouncer commandSyncDebouncer;

    /**
     * Creates a new SimpleCommandService.
     *
     * @param eventManager      the event manager
     * @param languageManager   the language manager
     * @param permissionManager the permission manager
     * @param configuration     the configuration
     * @param storageManager    the storage manager
     * @param defaultPrefix     the default command prefix
     */
    public SimpleCommandService(
            EventManager eventManager,
            LanguageManager languageManager,
            PermissionManager permissionManager,
            Configuration configuration,
            DataStorageManager storageManager,
            String defaultPrefix
    ) {
        this.eventManager = eventManager;
        this.languageManager = languageManager;
        this.permissionManager = permissionManager;
        this.configuration = configuration;
        this.storageManager = storageManager;
        this.defaultPrefix = defaultPrefix;

        this.registry = new SimpleCommandRegistry();

        // Register parsers
        parsers.add(new SlashCommandParser(languageManager));
        parsers.add(new TextCommandParser(languageManager, this));
        parsers.add(new ConsoleCommandParser(languageManager));

        // Initialize command sync debouncer
        this.commandSyncDebouncer = new Debouncer(SYNC_DELAY_MS, this::performSynchronization);
    }

    @Override
    public CommandRegistry getRegistry() {
        return registry;
    }

    @Override
    public CommandBuilder newCommand() {
        return new SimpleCommandBuilder();
    }

    @Override
    public String getPrefix() {
        return defaultPrefix;
    }

    @Override
    public void setPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("Prefix cannot be null or empty");
        }

        this.defaultPrefix = prefix;

        // Update configuration
        configuration.set("commands.default-prefix", prefix);
        try {
            configuration.save();
        } catch (ConfigurationException e) {
            logger.error("Failed to save command prefix to configuration", e);
        }
    }

    @Override
    public String getPrefix(String guildId) {
        if (guildId == null) {
            return defaultPrefix;
        }

        GuildStorage guildStorage = storageManager.getGuildStorage(guildId);
        return guildStorage.get("commands.prefix", String.class).orElse(defaultPrefix);
    }

    @Override
    public void setPrefix(String guildId, String prefix) {
        if (guildId == null) {
            setPrefix(prefix);
            return;
        }

        if (prefix == null || prefix.isEmpty()) {
            throw new IllegalArgumentException("Prefix cannot be null or empty");
        }

        GuildStorage guildStorage = storageManager.getGuildStorage(guildId);
        guildStorage.set("commands.prefix", prefix);
    }

    @Override
    public boolean registerCommand(Command command, Plugin plugin) {
        boolean result = registry.register(command, plugin);

        // Only use debouncer as last resort - during runtime command registration
        // Don't trigger debounced sync during initialization or if service is not fully ready
        if (result && isEnabled() && jda != null && jda.getStatus() == JDA.Status.CONNECTED && !duringInitialization) {
            scheduleDebouncedSync();
        }

        return result;
    }

    /**
     * Registers a command without associating it with a plugin.
     * This is used for system commands.
     *
     * @param command the command to register
     * @return true if the command was registered
     */
    public boolean registerCommand(Command command) {
        return registerCommand(command, null);
    }

    @Override
    public boolean registerCommand(Plugin plugin, Consumer<CommandBuilder> builderConsumer) {
        SimpleCommandBuilder builder = new SimpleCommandBuilder();
        builderConsumer.accept(builder);
        Command command = builder.build();
        return registerCommand(command, plugin);
    }

    /**
     * Registers a command without associating it with a plugin.
     * This is used for system commands.
     *
     * @param builderConsumer consumer to configure the command builder
     * @return true if the command was registered
     */
    public boolean registerCommand(Consumer<CommandBuilder> builderConsumer) {
        SimpleCommandBuilder builder = new SimpleCommandBuilder();
        builderConsumer.accept(builder);
        Command command = builder.build();
        return registerCommand(command);
    }

    @Override
    public CompletableFuture<Void> synchronizeCommands() {
        if (!isEnabled() || jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> globalFuture = synchronizeGlobalCommands();

        // Synchronize guild-specific commands
        Set<String> guildIds = new HashSet<>();
        for (Command command : registry.getCommands()) {
            guildIds.addAll(command.getGuildIds());
        }

        List<CompletableFuture<Void>> guildFutures = new ArrayList<>();
        for (String guildId : guildIds) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guildFutures.add(synchronizeGuildCommands(guild));
            }
        }

        // Return a future that completes when all futures complete
        CompletableFuture<Void> allGuildsFuture = CompletableFuture.allOf(
                guildFutures.toArray(new CompletableFuture[0]));

        return CompletableFuture.allOf(globalFuture, allGuildsFuture);
    }

    @Override
    public CompletableFuture<Void> synchronizeGuildCommands(Guild guild) {
        if (!isEnabled() || jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        List<CommandData> commandData = new ArrayList<>();

        // Collect commands for this guild
        for (Command command : registry.getCommands()) {
            if (!command.isEnabled()) {
                continue;
            }

            if (command.getGuildIds().contains(guild.getId())) {
                commandData.add(createCommandData(command));
            }
        }

        logger.info("Synchronizing {} guild commands for guild {}", commandData.size(), guild.getName());

        // Update the commands
        return guild.updateCommands().addCommands(commandData).submit()
                .thenRun(() -> logger.info("Guild commands synchronized for guild {}", guild.getName()));
    }

    @Override
    public CompletableFuture<Void> synchronizeGlobalCommands() {
        if (!isEnabled() || jda == null || jda.getStatus() != JDA.Status.CONNECTED) {
            return CompletableFuture.completedFuture(null);
        }

        List<CommandData> commandData = new ArrayList<>();

        // Collect global commands
        for (Command command : registry.getCommands()) {
            if (!command.isEnabled()) {
                continue;
            }

            if (command.getGuildIds().isEmpty() && !command.isSubcommand()) {
                commandData.add(createCommandData(command));
            }
        }

        logger.info("Synchronizing {} global commands", commandData.size());

        // Update the commands
        return jda.updateCommands().addCommands(commandData).submit()
                .thenRun(() -> logger.info("Global commands synchronized"));
    }

    @Override
    public void enable() {
        if (isEnabled()) {
            return;
        }

        // Mark that we're during initialization to avoid triggering debounced sync
        duringInitialization = true;
        enabled = true;

        // Register the command listener
        if (jda != null) {
            commandListener = new CommandListener(this);
            jda.addEventListener(commandListener);

            // Register system commands only once
            if (!systemCommandsRegistered) {
                registerSystemCommands();
                systemCommandsRegistered = true;
            }

            // Perform immediate synchronization instead of debounced during initialization
            // This ensures all commands are synced once at startup
            if (jda.getStatus() == JDA.Status.CONNECTED) {
                synchronizeCommands();
            }
        }

        // End of initialization - now runtime command registrations can use debouncer
        duringInitialization = false;

        logger.info("Command service enabled");
    }

    @Override
    public void disable() {
        if (!isEnabled()) {
            return;
        }

        enabled = false;

        // Shutdown the debouncer
        if (commandSyncDebouncer != null) {
            commandSyncDebouncer.shutdown();
            commandSyncDebouncer = new Debouncer(SYNC_DELAY_MS, this::performSynchronization);
        }

        // Unregister the command listener
        if (jda != null && commandListener != null) {
            jda.removeEventListener(commandListener);
            commandListener = null;
        }

        logger.info("Command service disabled");
    }

    @Override
    public JDA getJDA() {
        return jda;
    }

    @Override
    public void setJDA(JDA jda) {
        this.jda = jda;

        // Don't register commands or listeners here - let enable() handle everything
        // This avoids duplicate registrations and multiple sync calls
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public long getCommandExecutionCount() {
        return commandExecutionCount.get();
    }

    @Override
    public long getSuccessfulCommandExecutionCount() {
        return successfulCommandExecutionCount.get();
    }

    @Override
    public long getFailedCommandExecutionCount() {
        return failedCommandExecutionCount.get();
    }

    @Override
    public double getAverageExecutionTimeMs() {
        long count = commandExecutionCount.get();
        if (count == 0) {
            return 0;
        }

        return (double) totalExecutionTimeNs.get() / (count * 1_000_000);
    }

    @Override
    public boolean isOnCooldown(String userId, String commandName) {
        Map<String, Long> userCooldowns = cooldowns.get(userId);
        if (userCooldowns == null) {
            return false;
        }

        Long expirationTime = userCooldowns.get(commandName);
        if (expirationTime == null) {
            return false;
        }

        return expirationTime > System.currentTimeMillis();
    }

    @Override
    public int getRemainingCooldown(String userId, String commandName) {
        Map<String, Long> userCooldowns = cooldowns.get(userId);
        if (userCooldowns == null) {
            return 0;
        }

        Long expirationTime = userCooldowns.get(commandName);
        if (expirationTime == null) {
            return 0;
        }

        long remaining = expirationTime - System.currentTimeMillis();
        return remaining > 0 ? (int) (remaining / 1000) : 0;
    }

    /**
     * Executes a command with the given context.
     * This method is called by the command listener.
     *
     * @param command the command to execute
     * @param context the command context
     * @return the command result
     */
    public CommandResult executeCommand(Command command, CommandContext context) {
        Locale locale = context.getLocale();

        if (!isEnabled()) {
            return CommandResult.error(languageManager.getString(locale, "commands.messages.system_disabled"));
        }

        // Check if the command is enabled
        if (!command.isEnabled()) {
            return CommandResult.error(languageManager.getString(locale, "commands.messages.disabled"));
        }

        // Determine if this is a console command (user is null)
        boolean isConsoleCommand = context.getUser() == null;

        // Check guild-only (skip for console commands - they are not bound to guilds)
        if (command.isGuildOnly() && !context.isFromGuild() && !isConsoleCommand) {
            return CommandResult.error(languageManager.getString(locale, "commands.messages.guild_only"));
        }

        String userId = isConsoleCommand ? "CONSOLE" : context.getUser().getId();

        // Check admin permission (skip for console commands - they are trusted)
        if (command.getPermission() != null && !isConsoleCommand) {
            String guildId = context.getGuild().map(Guild::getId).orElse(null);

            try {
                if (!permissionManager.hasPermission(userId, guildId, command.getPermission())) {
                    throw new CommandPermissionException(
                            "You don't have permission to use this command", command.getPermission());
                }
            } catch (Exception e) {
                return CommandResult.error(languageManager.getString(locale, "commands.messages.permission_error", e.getMessage()));
            }
        }

        // Check cooldown (skip for console commands)
        if (!isConsoleCommand && isOnCooldown(userId, command.getName())) {
            int seconds = getRemainingCooldown(userId, command.getName());
            return CommandResult.error(languageManager.getString(locale, "commands.messages.cooldown", seconds));
        }

        // Fire command execute event
        CommandExecuteEvent executeEvent = new CommandExecuteEvent(command, context);
        eventManager.fireEvent(executeEvent);

        // Check if the event was cancelled
        if (executeEvent.isCancelled()) {
            return CommandResult.error(languageManager.getString(locale, "commands.messages.execution_cancelled"));
        }

        // Execute the command
        CommandResult result;
        long startTime = System.nanoTime();

        try {
            result = command.execute(context);

            // Apply cooldown if specified (skip for console commands)
            if (command.getCooldown() > 0 && !isConsoleCommand) {
                applyCooldown(userId, command.getName(), command.getCooldown());
            }
        } catch (Exception e) {
            logger.error("Error executing command {}: {}", command.getName(), e.getMessage(), e);
            result = CommandResult.error(languageManager.getString(locale, "commands.messages.execution_error", e.getMessage()));
        }

        long endTime = System.nanoTime();
        long executionTimeNs = endTime - startTime;

        // Update statistics
        commandExecutionCount.incrementAndGet();
        totalExecutionTimeNs.addAndGet(executionTimeNs);

        if (result.isSuccess()) {
            successfulCommandExecutionCount.incrementAndGet();
        } else {
            failedCommandExecutionCount.incrementAndGet();
        }

        // Fire command executed event
        CommandExecutedEvent executedEvent = new CommandExecutedEvent(
                command, context, result, executionTimeNs / 1_000_000);
        eventManager.fireEvent(executedEvent);

        return result;
    }

    /**
     * Applies a cooldown to a command for a user.
     *
     * @param userId          the user ID
     * @param commandName     the command name
     * @param cooldownSeconds the cooldown in seconds
     */
    private void applyCooldown(String userId, String commandName, int cooldownSeconds) {
        long expirationTime = System.currentTimeMillis() + (cooldownSeconds * 1000L);
        cooldowns.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(commandName, expirationTime);
    }

    /**
     * Builds JDA option data from a command option.
     *
     * @param option the command option
     * @return the JDA option data
     */
    private OptionData buildOptionData(CommandOption<?> option) {
        OptionData optionData = new OptionData(
                convertOptionType(option.getType()),
                option.getName(),
                option.getDescription(),
                option.isRequired()
        );

        // Add min/max values for number options
        if (option.getMinValue() != null) {
            if (option.getType() == OptionType2.INTEGER) {
                optionData.setMinValue(option.getMinValue().longValue());
            } else if (option.getType() == OptionType2.NUMBER) {
                optionData.setMinValue(option.getMinValue().doubleValue());
            }
        }

        if (option.getMaxValue() != null) {
            if (option.getType() == OptionType2.INTEGER) {
                optionData.setMaxValue(option.getMaxValue().longValue());
            } else if (option.getType() == OptionType2.NUMBER) {
                optionData.setMaxValue(option.getMaxValue().doubleValue());
            }
        }

        // Add min/max length for string options
        if (option.getMinLength() != null) {
            optionData.setMinLength(option.getMinLength());
        }

        if (option.getMaxLength() != null) {
            optionData.setMaxLength(option.getMaxLength());
        }

        // Add choices
        if (!option.getChoices().isEmpty()) {
            for (OptionChoice<?> choice : option.getChoices()) {
                if (choice.value() instanceof String string) {
                    optionData.addChoice(choice.name(), string);
                } else if (choice.value() instanceof Integer integer) {
                    optionData.addChoice(choice.name(), integer);
                } else if (choice.value() instanceof Double doubleValue) {
                    optionData.addChoice(choice.name(), doubleValue);
                }
            }
        }

        // Enable autocomplete
        if (option.getAutocompleteProvider() != null) {
            optionData.setAutoComplete(true);
        }

        // Restrict accepted file types for attachment options (JDA 6.6+)
        if (option.getType() == OptionType2.ATTACHMENT && !option.getFileTypes().isEmpty()) {
            optionData.addFileTypes(option.getFileTypes().stream()
                    .map(SimpleCommandService::toFileType)
                    .toList());
        }

        return optionData;
    }

    /**
     * Converts a Fluxcord file type string to a JDA {@link FileType}.
     * Generic categories map to JDA's constants; anything else is treated as a file extension.
     *
     * @param fileType a category ({@code image}, {@code video}, {@code audio}) or an extension without dot
     * @return the JDA file type
     */
    private static FileType toFileType(String fileType) {
        return switch (fileType.toLowerCase(Locale.ROOT)) {
            case "image" -> FileType.IMAGE;
            case "video" -> FileType.VIDEO;
            case "audio" -> FileType.AUDIO;
            default -> FileType.ofExtension(fileType);
        };
    }

    /**
     * Builds JDA subcommand data from a command.
     *
     * @param subcommand the subcommand
     * @return the JDA subcommand data
     */
    private SubcommandData buildSubcommandData(Command subcommand) {
        SubcommandData subcommandData = new SubcommandData(
                subcommand.getName().toLowerCase(),
                subcommand.getDescription()
        );

        // Add options to subcommand
        for (CommandOption<?> option : subcommand.getOptions()) {
            OptionData optionData = new OptionData(
                    convertOptionType(option.getType()),
                    option.getName(),
                    option.getDescription(),
                    option.isRequired()
            );

            subcommandData.addOptions(optionData);
        }

        return subcommandData;
    }

    /**
     * Builds JDA subcommand group data from a command.
     *
     * @param command the command containing the subcommands
     * @return the JDA subcommand group data
     */
    private SubcommandGroupData buildSubcommandGroupData(Command command) {
        SubcommandGroupData groupData = new SubcommandGroupData(
                command.getGroup().toLowerCase(),
                command.getDescription()
        );

        for (Command subcommand : command.getSubcommands()) {
            groupData.addSubcommands(buildSubcommandData(subcommand));
        }

        return groupData;
    }

    /**
     * Creates JDA command data from a command.
     *
     * @param command the command
     * @return the command data
     */
    private CommandData createCommandData(Command command) {
        SlashCommandData data = Commands.slash(command.getName().toLowerCase(), command.getDescription());

        // Add options
        for (CommandOption<?> option : command.getOptions()) {
            data.addOptions(buildOptionData(option));
        }

        // Add subcommands
        if (!command.getSubcommands().isEmpty()) {
            // Group subcommands if a group is specified
            if (command.getGroup() != null) {
                data.addSubcommandGroups(buildSubcommandGroupData(command));
            } else {
                // Add subcommands directly
                for (Command subcommand : command.getSubcommands()) {
                    data.addSubcommands(buildSubcommandData(subcommand));
                }
            }
        }

        // Set default permissions
        if (command.getPermission() != null) {
            data.setDefaultPermissions(DefaultMemberPermissions.DISABLED);
        } else {
            data.setDefaultPermissions(DefaultMemberPermissions.ENABLED);
        }

        // Set context types (replaces setGuildOnly)
        if (command.isGuildOnly()) {
            data.setContexts(InteractionContextType.GUILD);
        } else {
            data.setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL);
        }

        return data;
    }

    /**
     * Converts an option type to a JDA option type.
     *
     * @param type the option type
     * @return the JDA option type
     */
    private OptionType convertOptionType(OptionType2 type) {
        return type.getJdaType();
    }

    /**
     * Registers system commands.
     */
    private void registerSystemCommands() {
        // Register help command if enabled
        if (configuration.getBoolean("commands.system.help", true)) {
            registerCommand(new HelpCommand(this, languageManager).getCommand());
        }

        // Register version command if enabled
        if (configuration.getBoolean("commands.system.version", true)) {
            registerCommand(new VersionCommand(languageManager).getCommand());
        }

        // Register shutdown command if enabled
        if (configuration.getBoolean("commands.system.shutdown", true)) {
            registerCommand(new ShutdownCommand(languageManager, permissionManager).getCommand());
        }

        // Register perm command if enabled
        if (configuration.getBoolean("commands.system.perm", true)) {
            registerCommand(new PermCommand(languageManager, permissionManager).getCommand());
        }
    }

    /**
     * Schedules a debounced synchronization to avoid rapid API calls.
     * Uses the existing Debouncer utility class as requested.
     */
    private void scheduleDebouncedSync() {
        if (commandSyncDebouncer != null) {
            logger.debug("Scheduling debounced command synchronization in {}ms", SYNC_DELAY_MS);
            commandSyncDebouncer.debounce();
        }
    }

    /**
     * Performs the actual synchronization - used by the debouncer.
     */
    private void performSynchronization() {
        try {
            logger.debug("Executing debounced command synchronization");
            synchronizeCommands().join(); // Wait for completion
        } catch (Exception e) {
            logger.error("Error during debounced command synchronization", e);
        }
    }

    /**
     * Processes a command from a JDA event.
     * This method is called by the command listener.
     *
     * @param event the JDA event
     */
    public void processCommand(net.dv8tion.jda.api.events.Event event) {
        if (!isEnabled()) {
            return;
        }

        logger.debug("Processing event of type {}", event.getClass().getSimpleName());

        // Find a parser that can handle this event
        for (CommandParser parser : parsers) {
            if (parser.canParse(event)) {
                logger.debug("Parser {} can handle event type {}", parser.getClass().getSimpleName(), event.getClass().getSimpleName());

                if (parser.isCommandInvocation(event)) {
                    logger.debug("Parser {} detected command invocation", parser.getClass().getSimpleName());

                    try {
                        // Extract the command name
                        String commandName = parser.extractCommandName(event);
                        logger.debug("Extracted command name: '{}'", commandName);

                        // Find the command
                        Command command = registry.getCommand(commandName)
                                .orElseGet(() -> registry.getCommandByAlias(commandName).orElse(null));

                        if (command == null) {
                            // Unknown command - log for debugging
                            logger.debug("Unknown command '{}' attempted via {}", commandName, parser.getClass().getSimpleName());
                            continue;
                        }

                        logger.debug("Found command '{}', executing...", command.getName());

                        // Parse the command
                        CommandContext context = parser.parse(event, command);

                        // Execute the command (commands should handle their own deferral if needed)
                        CommandResult result = executeCommand(command, context);

                        // Handle replies based on command result and context
                        if (event instanceof net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent slashEvent) {
                            if (slashEvent.isAcknowledged()) {
                                // Command was deferred - check if we need to send a response
                                if (!result.isSuccess() && result.getErrorMessage() != null) {
                                    context.replyError(result.getErrorMessage());
                                }
                                // For successful commands, assume they handled their own reply through context
                                // If they didn't, the deferred interaction will remain as "Bot is thinking..."
                                // which is acceptable for commands that don't need explicit confirmation
                            }
                            // If not acknowledged, the reply was sent directly by the command
                        } else {
                            // For text and console commands, only reply on error if no explicit reply was sent
                            if (!result.isSuccess() && result.getErrorMessage() != null) {
                                context.replyError(result.getErrorMessage());
                            }
                        }

                        logger.debug("Command '{}' executed with success: {}", command.getName(), result.isSuccess());

                        // We found and executed a command, so we're done
                        return;
                    } catch (CommandParseException e) {
                        // Failed to parse the command - try the next parser
                        logger.debug("Failed to parse command with {}: {}", parser.getClass().getSimpleName(), e.getMessage());
                    } catch (Exception e) {
                        // Something went wrong - log and continue
                        logger.error("Error processing command with {}: {}", parser.getClass().getSimpleName(), e.getMessage(), e);
                    }
                }
            }
        }
    }
}
