package fr.farmvivi.fluxcord.core;

import fr.farmvivi.fluxcord.core.config.CoreConfiguration;
import fr.farmvivi.fluxcord.core.discord.JDADiscordAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Entry point: reads {@code config.yml} from the working directory, builds a {@link FluxcordRuntime} on the real
 * Discord connection, starts it, then parks until a shutdown is requested (shutdown command, console, SIGTERM)
 * and stops it from the main thread. Every failure before the bot is up ends the JVM with exit code 1.
 */
public final class Fluxcord {
    public static final String NAME;
    public static final String VERSION;
    /** True for release builds (version without {@code -SNAPSHOT}). */
    public static final boolean PRODUCTION;
    private static final Logger logger;

    static {
        Properties properties = new Properties();
        try (InputStream in = Fluxcord.class.getClassLoader().getResourceAsStream("project.properties")) {
            if (in == null) {
                throw new IOException("project.properties not found on the classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            LoggerFactory.getLogger("FluxcordInit").error("Cannot read properties file 'project.properties'", e);
            System.exit(1);
        }
        NAME = properties.getProperty("name");
        VERSION = properties.getProperty("version");
        PRODUCTION = !VERSION.contains("-SNAPSHOT");
        logger = LoggerFactory.getLogger(NAME);
    }

    private Fluxcord() {
    }

    public static void main(String[] args) {
        long startTimeMillis = System.currentTimeMillis();
        logger.info("Démarrage de {} v{} en cours...", NAME, VERSION);
        logSystemInfo();

        FluxcordRuntime runtime;
        try {
            File baseDir = new File(".").getAbsoluteFile().getParentFile();
            CoreConfiguration config = new CoreConfiguration(new File(baseDir, "config.yml"));
            String token = config.getString("discord.token");
            if (token == null || token.isBlank() || token.equals("YOUR_BOT_TOKEN")) {
                logger.error("Please set your bot token in config.yml");
                System.exit(1);
                return;
            }
            runtime = new FluxcordRuntime(baseDir, config, new JDADiscordAPI(token));
        } catch (Exception e) {
            logger.error("Failed to initialise: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        // SIGTERM / Ctrl+C / System.exit from elsewhere: same sequence, skipped if it already ran
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::stop, "Fluxcord-Shutdown"));
        runtime.startHealthServer(healthPort());

        try {
            runtime.start();
        } catch (Exception e) {
            logger.error("Failed to start: {}", e.getMessage(), e);
            runtime.stop();
            System.exit(1);
            return;
        }
        logger.info("Started in {}s!", (float) (System.currentTimeMillis() - startTimeMillis) / 1000);

        // Park the main thread until a shutdown is requested, then stop everything from here rather than from
        // an arbitrary thread through System.exit + shutdown hook
        try {
            runtime.awaitShutdownRequest();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runtime.stop();
        System.exit(0);
    }

    /** {@code HEALTH_PORT} environment variable, default 8081. */
    static int healthPort() {
        String envPort = System.getenv("HEALTH_PORT");
        if (envPort == null || envPort.isBlank()) {
            return 8081;
        }
        try {
            return Integer.parseInt(envPort.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid HEALTH_PORT '{}', falling back to 8081", envPort);
            return 8081;
        }
    }

    private static void logSystemInfo() {
        if (logger.isInfoEnabled()) {
            for (String key : new String[]{"os.name", "os.version", "os.arch", "java.version", "java.vendor",
                    "sun.arch.data.model", "user.timezone", "user.country", "user.language"}) {
                logger.info("System.getProperty('{}') == '{}'", key, System.getProperty(key));
            }
        }
    }
}
