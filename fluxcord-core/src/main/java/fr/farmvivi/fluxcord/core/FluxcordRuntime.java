package fr.farmvivi.fluxcord.core;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.core.audio.AudioServiceImpl;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.console.ConsoleCommandService;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.health.HealthServer;
import fr.farmvivi.fluxcord.core.language.LanguageFiles;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.plugin.PluginManager;
import fr.farmvivi.fluxcord.core.storage.StorageFactory;
import fr.farmvivi.fluxcord.core.storage.binary.BinaryStorageFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One running instance of the engine: every service wired from {@link CoreSettings} and a {@link DiscordAPI},
 * with the start and stop sequences. {@link Fluxcord#main} builds one from {@code config.yml} and the real
 * {@code JDADiscordAPI}; tests build one from a temporary directory and a fake Discord.
 * <p>
 * Directory layout, relative to {@code baseDir}: {@code plugins/} (plugin jars and their data), {@code lang/}
 * (language overrides); storage folders come from the settings (already resolved against the base directory).
 */
public final class FluxcordRuntime {
    private static final Logger logger = LoggerFactory.getLogger(FluxcordRuntime.class);

    private final File baseDir;
    private final CoreSettings settings;
    private final DiscordAPI discordAPI;
    private final SimpleEventManager eventManager;
    private final SimpleLanguageManager languageManager;
    private final DataStorageManager dataStorageManager;
    private final BinaryStorageManager binaryStorageManager;
    private final SimplePermissionManager permissionManager;
    private final AudioServiceImpl audioService;
    private final SimpleCommandService commandService;
    private final ConsoleCommandService consoleCommandService;
    private final PluginManager pluginManager;

    private HealthServer healthServer;
    private final CountDownLatch shutdownRequested = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile boolean started;

    /**
     * Wires every service. Nothing touches Discord or the plugins yet: see {@link #start()}.
     *
     * @param baseDir    the working directory ({@code plugins/}, {@code lang/} live there)
     * @param settings   the validated core settings ({@link CoreSettings#from})
     * @param discordAPI the Discord connection to use (real JDA in production)
     * @throws RuntimeException when a storage backend cannot be initialised (see {@link StorageFactory})
     */
    public FluxcordRuntime(File baseDir, CoreSettings settings, DiscordAPI discordAPI) {
        this(baseDir, settings, discordAPI, System.in);
    }

    /**
     * Same as {@link #FluxcordRuntime(File, CoreSettings, DiscordAPI)} with an explicit console input. Tests pass
     * their own stream: a runtime reading {@code System.in} inside a surefire fork steals the channel surefire
     * uses to acknowledge the JVM exit, which then hangs 30 s and is killed before JaCoCo can write its dump.
     *
     * @param consoleInput where the console commands are read from (one command per line)
     */
    public FluxcordRuntime(File baseDir, CoreSettings settings, DiscordAPI discordAPI, InputStream consoleInput) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.discordAPI = Objects.requireNonNull(discordAPI, "discordAPI");

        File pluginsFolder = new File(baseDir, "plugins");
        if (!pluginsFolder.isDirectory() && !pluginsFolder.mkdirs()) {
            logger.warn("Could not create the plugins folder {}", pluginsFolder.getAbsolutePath());
        }

        this.eventManager = new SimpleEventManager();
        this.languageManager = new SimpleLanguageManager(settings.defaultLocale(), eventManager);
        LanguageFiles.loadFolder(languageManager, SimpleLanguageManager.CORE_NAMESPACE, new File(baseDir, "lang"));

        this.dataStorageManager = StorageFactory.createStorageManager(settings.dataStorage(), eventManager);
        this.binaryStorageManager = BinaryStorageFactory.createBinaryStorageManager(settings.binaryStorage(), eventManager);
        this.permissionManager = new SimplePermissionManager(eventManager, dataStorageManager, settings.operators());
        this.audioService = new AudioServiceImpl(eventManager, settings.audio());

        this.commandService = new SimpleCommandService(eventManager, languageManager, permissionManager,
                settings.commands(), dataStorageManager);
        this.commandService.setShutdownHandler(this::requestShutdown);
        this.consoleCommandService = new ConsoleCommandService(commandService, consoleInput);

        this.pluginManager = new PluginManager(pluginsFolder, eventManager, discordAPI, languageManager,
                dataStorageManager, binaryStorageManager, permissionManager, audioService, commandService);
    }

    /**
     * Serves {@code /healthz}, {@code /readyz} (503 until {@link #start()} completed) and {@code /version}.
     * Optional; a failure to bind is logged, not fatal.
     *
     * @param port the port, 0 for an ephemeral one
     */
    public void startHealthServer(int port) {
        try {
            healthServer = new HealthServer(port);
            healthServer.setVersion(Fluxcord.VERSION);
            healthServer.start();
        } catch (Exception e) {
            logger.warn("Failed to start health server on port {}: {}", port, e.getMessage());
            healthServer = null;
        }
    }

    /**
     * Boot sequence: load + pre-enable plugins (they may still configure the JDA builder), connect, hand JDA to
     * the services, enable plugins, sync commands, post-enable, start the console, mark ready.
     *
     * @throws IllegalStateException when already started
     * @throws RuntimeException      when the Discord connection fails; the caller should {@link #stop()}
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("Runtime already started");
        }
        started = true;

        pluginManager.loadPlugins();
        pluginManager.preEnablePlugins();

        discordAPI.connect().join();
        discordAPI.setStartupPresence();
        JDA jda = discordAPI.getJDA();
        commandService.setJDA(jda);
        consoleCommandService.setJDA(jda);
        permissionManager.setGuildOperatorResolver((userId, guildId) -> isGuildOperator(jda, userId, guildId));

        pluginManager.enablePlugins();
        commandService.enable();
        pluginManager.postEnablePlugins();
        consoleCommandService.start();
        discordAPI.setDefaultPresence();

        if (healthServer != null) {
            healthServer.setReady(true);
        }
    }

    /** Guild-level operators: the owner and ADMINISTRATOR members, resolved from the JDA cache (no REST). */
    static boolean isGuildOperator(JDA jda, String userId, String guildId) {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return false;
        }
        if (guild.getOwnerId().equals(userId)) {
            return true;
        }
        Member member = guild.getMemberById(userId);
        return member != null && member.hasPermission(Permission.ADMINISTRATOR);
    }

    /**
     * Asks for a shutdown; returns immediately. Whoever called {@link #awaitShutdownRequest()} (the main
     * thread) then runs {@link #stop()}.
     */
    public void requestShutdown() {
        shutdownRequested.countDown();
    }

    /** Blocks until {@link #requestShutdown()} is called. */
    public void awaitShutdownRequest() throws InterruptedException {
        shutdownRequested.await();
    }

    /**
     * Shutdown sequence, run at most once and safe on a half-started runtime: plugins, command service,
     * console, Discord, events, storages, health server. Never throws.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        logger.info("Shutting down...");
        // Every step guarded, and the order kept. Six of these were unguarded, so anything thrown by one of
        // them skipped all the rest - including the disconnection from Discord, which is the step that
        // matters: a bot that does not disconnect keeps its gateway session, and with one Discord token
        // shared between a local run and a deployment, a process nobody can see is dead is a process that
        // answers every command twice.
        //
        // MEASURED 2026-11-07: a NoClassDefFoundError out of a plugin's onDisable came all the way up here
        // and killed the calling thread, which was main. No disconnection, no "Goodbye!", and the
        // non-daemon threads held the JVM - and the token - for seven hours.
        quietly("disabling the plugins", pluginManager::close);
        quietly("disabling the commands", commandService::disable);
        quietly("stopping the console", consoleCommandService::stop);
        quietly("disconnecting from Discord", () -> discordAPI.disconnect().join());
        quietly("shutting down the events", eventManager::shutdown);
        quietly("closing the data storage", dataStorageManager::close);
        quietly("closing the binary storage", binaryStorageManager::close);
        if (healthServer != null) {
            quietly("stopping the health server", healthServer::stop);
        }
        logger.info("Goodbye!");
    }

    /**
     * Runs one shutdown step, and lets nothing it throws stop the next one.
     *
     * <p>{@link Throwable} on purpose, which is not how application code should normally catch. Shutdown is
     * the last chance for a class nobody needed during the run to be loaded, so it is where a
     * {@link LinkageError} appears - and an {@code Error} let through here costs the disconnection from
     * Discord and every release after it. The JVM is on its way out; there is nothing left to protect by
     * propagating.
     *
     * @param what the step, named for the log line that will explain the failure
     * @param step what to do
     */
    private void quietly(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable e) {
            logger.error("Shutdown continued after a failure while {}", what, e);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isStopped() {
        return stopped.get();
    }

    // ---- services ---------------------------------------------------------------------------------------------

    public File getBaseDir() {
        return baseDir;
    }

    public CoreSettings getSettings() {
        return settings;
    }

    public DiscordAPI getDiscordAPI() {
        return discordAPI;
    }

    public SimpleEventManager getEventManager() {
        return eventManager;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public DataStorageManager getDataStorageManager() {
        return dataStorageManager;
    }

    public BinaryStorageManager getBinaryStorageManager() {
        return binaryStorageManager;
    }

    public SimplePermissionManager getPermissionManager() {
        return permissionManager;
    }

    public AudioService getAudioService() {
        return audioService;
    }

    public CommandService getCommandService() {
        return commandService;
    }

    public ConsoleCommandService getConsoleCommandService() {
        return consoleCommandService;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public HealthServer getHealthServer() {
        return healthServer;
    }
}
