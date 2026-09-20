package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.*;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
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
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Implementation of the CommandService interface.
 * This service manages the lifecycle of commands, including registration,
 * execution, and synchronization with Discord.
 */
public class SimpleCommandService implements CommandService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleCommandService.class);
    private static final long SYNC_DELAY_MS = 1000; // 1 second delay
    private static final int MAX_AUTOCOMPLETE_CHOICES = 25; // Discord limit
    private final CommandRegistry registry;
    private final List<CommandParser> parsers = new ArrayList<>();
    private final EventManager eventManager;
    private final LanguageManager languageManager;
    private final PermissionManager permissionManager;
    private final Configuration configuration;
    private final DataStorageManager storageManager;
    // Statistics
    private final CommandExecutor executor;
    // Cooldowns: userId -> (commandName -> expirationTime)
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
        this.executor = new CommandExecutor(eventManager, languageManager, permissionManager, this::isEnabled);

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

        ScopedStorage guildStorage = storageManager.getGuildStorage(guildId);
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

        ScopedStorage guildStorage = storageManager.getGuildStorage(guildId);
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
                commandData.add(SlashCommandDataMapper.toCommandData(command));
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
                commandData.add(SlashCommandDataMapper.toCommandData(command));
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
        return executor.getExecutionCount();
    }

    @Override
    public long getSuccessfulCommandExecutionCount() {
        return executor.getSuccessCount();
    }

    @Override
    public long getFailedCommandExecutionCount() {
        return executor.getFailureCount();
    }

    @Override
    public double getAverageExecutionTimeMs() {
        return executor.getAverageExecutionTimeMs();
    }

    @Override
    public boolean isOnCooldown(String userId, String commandName) {
        return executor.isOnCooldown(userId, commandName);
    }

    @Override
    public int getRemainingCooldown(String userId, String commandName) {
        return executor.getRemainingCooldown(userId, commandName);
    }

    /**
     * Runs a parsed command through the execution pipeline (see {@link CommandExecutor}).
     *
     * @param command the command to execute
     * @param context the command context
     * @return the command result; on refusal, its error message is localised and ready to be shown
     */
    public CommandResult executeCommand(Command command, CommandContext context) {
        return executor.execute(command, context);
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
     * Answers a Discord autocomplete request: finds the (sub)command and the focused option, asks its
     * {@link AutocompleteProvider} and replies with at most 25 choices. Any failure answers an empty list
     * (Discord shows nothing instead of "loading options failed").
     *
     * @param event the JDA autocomplete event
     */
    public void handleAutocomplete(CommandAutoCompleteInteractionEvent event) {
        List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices;
        try {
            choices = suggest(event.getName(), event.getSubcommandName(), event.getFocusedOption().getName(),
                    new AutocompleteContext(event.getFocusedOption().getValue(),
                            event.getGuild() != null ? event.getGuild().getId() : null,
                            event.getUser().getId(),
                            event.getOptions().stream().collect(java.util.stream.Collectors.toMap(
                                    OptionMapping::getName, OptionMapping::getAsString, (a, b) -> a))))
                    .stream()
                    .map(SimpleCommandService::toJdaChoice)
                    .toList();
        } catch (RuntimeException e) {
            logger.warn("Autocomplete provider failed for /{} option {}", event.getName(), event.getFocusedOption().getName(), e);
            choices = List.of();
        }
        event.replyChoices(choices).queue(null, t -> logger.debug("Autocomplete reply failed (interaction expired?)", t));
    }

    /** Discord needs the value type to match the option type: numbers stay numbers, everything else is a string. */
    private static net.dv8tion.jda.api.interactions.commands.Command.Choice toJdaChoice(OptionChoice<?> choice) {
        Object value = choice.value();
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return new net.dv8tion.jda.api.interactions.commands.Command.Choice(choice.name(), ((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return new net.dv8tion.jda.api.interactions.commands.Command.Choice(choice.name(), number.doubleValue());
        }
        return new net.dv8tion.jda.api.interactions.commands.Command.Choice(choice.name(), String.valueOf(value));
    }

    /**
     * Suggestions for one option of a registered command, capped to Discord's limit of 25 choices.
     * Package-private for tests; {@code subcommandName} may be null.
     */
    List<OptionChoice<?>> suggest(String commandName, String subcommandName, String optionName, AutocompleteContext context) {
        Command command = registry.getCommand(commandName).orElse(null);
        if (command != null && subcommandName != null) {
            command = command.getSubcommands().stream()
                    .filter(sub -> sub.getName().equalsIgnoreCase(subcommandName))
                    .findFirst().orElse(null);
        }
        if (command == null) {
            return List.of();
        }
        CommandOption<?> option = command.getOptions().stream()
                .filter(o -> o.getName().equalsIgnoreCase(optionName))
                .findFirst().orElse(null);
        if (option == null || option.getAutocompleteProvider() == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<OptionChoice<?>> suggestions = (List<OptionChoice<?>>) (List<?>) option.getAutocompleteProvider().suggest(context);
        if (suggestions == null) {
            return List.of();
        }
        return suggestions.size() > MAX_AUTOCOMPLETE_CHOICES ? suggestions.subList(0, MAX_AUTOCOMPLETE_CHOICES) : suggestions;
    }

    /**
     * Entry point for the three front-ends (slash interaction, prefixed message, console line). The first parser
     * that recognises the event owns it: an unknown command is ignored, a parse failure (missing/invalid option)
     * is answered with a usage error, a refusal or failed execution is answered with its error message, and a
     * successful execution is left to the command (which may have replied or deferred itself).
     *
     * @param event the JDA event
     */
    public void processCommand(net.dv8tion.jda.api.events.Event event) {
        if (!isEnabled()) {
            return;
        }
        for (CommandParser parser : parsers) {
            if (parser.canParse(event) && parser.isCommandInvocation(event)) {
                dispatch(parser, event);
                return;
            }
        }
    }

    private void dispatch(CommandParser parser, net.dv8tion.jda.api.events.Event event) {
        String parserName = parser.getClass().getSimpleName();
        String commandName;
        try {
            commandName = parser.extractCommandName(event);
        } catch (CommandParseException e) {
            logger.debug("{}: not a command invocation ({})", parserName, e.getMessage());
            return;
        }

        Command command = registry.getCommand(commandName)
                .orElseGet(() -> registry.getCommandByAlias(commandName).orElse(null));
        if (command == null) {
            logger.debug("Unknown command '{}' via {}", commandName, parserName);
            return;
        }

        CommandContext context;
        try {
            context = parser.parse(event, command);
        } catch (CommandParseException e) {
            logger.debug("{}: cannot parse '{}': {}", parserName, commandName, e.getMessage());
            replyParseError(event, e.getMessage());
            return;
        } catch (Exception e) {
            logger.error("{}: error parsing '{}': {}", parserName, commandName, e.getMessage(), e);
            replyParseError(event, e.getMessage());
            return;
        }

        CommandResult result = executeCommand(command, context);
        logger.debug("Command '{}' executed with success: {}", command.getName(), result.isSuccess());
        if (!result.isSuccess() && result.getErrorMessage() != null && !context.hasReplied()) {
            // Refusals never touched the interaction, so this is the first (and only) reply; a deferred command
            // that failed gets its "thinking..." placeholder edited into the error. A command that already
            // explained its failure itself (e.g. a usage message) is left alone.
            try {
                context.replyError(result.getErrorMessage());
            } catch (Exception e) {
                logger.error("Could not reply to the failed command '{}': {}", command.getName(), e.getMessage(), e);
            }
        }
    }

    /** Answers an unparsable invocation on its own transport (no {@link CommandContext} exists yet). */
    private void replyParseError(net.dv8tion.jda.api.events.Event event, String reason) {
        Locale locale = event instanceof net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent slash
                ? slash.getUserLocale().toLocale() : languageManager.getDefaultLocale();
        try {
            CommandMessageBuilder message = new CommandMessageBuilder(event, languageManager, locale);
            message.setEphemeral(true);
            message.error(languageManager.getString(locale, "commands.messages.parse_error", reason));
            message.replyNow();
        } catch (Exception e) {
            logger.error("Could not reply with a usage error: {}", e.getMessage(), e);
        }
    }
}
