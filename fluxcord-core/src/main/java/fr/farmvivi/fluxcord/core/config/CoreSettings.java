package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import fr.farmvivi.fluxcord.core.audio.AudioSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Everything the core reads from {@code config.yml}, parsed and validated once by {@link #from(Configuration, File)}.
 * Services receive the record they need instead of the raw {@link Configuration} and its string keys; the keys
 * themselves live only here (and in the default {@code config.yml}).
 * <p>
 * Relative folders are resolved against the base directory; secrets (token, DB password, S3 keys) are held as
 * plain strings and must not be logged.
 *
 * @param token         {@code discord.token}
 * @param defaultLocale {@code language.default} (invalid tag → {@code en-US}, with a warning)
 * @param commands      {@code commands.*}
 * @param operators     {@code permissions.operators}: Discord user ids that are operators everywhere
 * @param dataStorage   {@code data.storage.*}
 * @param binaryStorage {@code data.binary.storage.*}
 * @param audio         {@code audio.*}
 */
public record CoreSettings(
        String token,
        Locale defaultLocale,
        Commands commands,
        List<String> operators,
        DataStorage dataStorage,
        BinaryStorage binaryStorage,
        AudioSettings audio
) {
    private static final Logger logger = LoggerFactory.getLogger(CoreSettings.class);
    /** The placeholder shipped in the default {@code config.yml}. */
    public static final String TOKEN_PLACEHOLDER = "YOUR_BOT_TOKEN";

    public CoreSettings {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        Objects.requireNonNull(commands, "commands");
        operators = List.copyOf(operators);
        Objects.requireNonNull(dataStorage, "dataStorage");
        Objects.requireNonNull(binaryStorage, "binaryStorage");
        Objects.requireNonNull(audio, "audio");
    }

    /** {@code commands.*}: the text-command prefix and which built-in commands are registered. */
    public record Commands(String defaultPrefix, boolean help, boolean version, boolean shutdown, boolean perm) {
        public Commands {
            if (defaultPrefix == null || defaultPrefix.isEmpty()) {
                throw new IllegalArgumentException("commands.default-prefix cannot be empty");
            }
        }
    }

    public enum DataBackend { FILE, DB }

    /**
     * {@code data.storage.*}.
     *
     * @param type       the backend
     * @param fallback   degrade to FILE when the DB is unreachable at boot
     * @param fileFolder root of the FILE backend (its {@code storage/} subfolder holds the scopes); absolute
     * @param debounceMs FILE backend write debounce
     * @param database   validated only when {@code type == DB}
     */
    public record DataStorage(DataBackend type, boolean fallback, File fileFolder, long debounceMs, Database database) {
        public DataStorage {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(fileFolder, "fileFolder");
            if (debounceMs < 0) {
                throw new IllegalArgumentException("data.storage.file.debounce_ms cannot be negative");
            }
        }
    }

    /**
     * {@code data.storage.db.*}.
     *
     * <p>{@code maxPoolSize} and {@code autoCommit} used to be boxed and null when unset, but the pool
     * substituted a default for null anyway — so the nullability bought nothing and invited an unboxing
     * NPE. The defaults are applied when the configuration is read instead.
     */
    public record Database(String url, String username, String password, String tablePrefix,
                           int maxPoolSize, boolean autoCommit) {
        /** What HikariCP is given when {@code data.storage.db.max_pool_size} is absent. */
        public static final int DEFAULT_MAX_POOL_SIZE = 10;
        /** What HikariCP is given when {@code data.storage.db.auto_commit} is absent. */
        public static final boolean DEFAULT_AUTO_COMMIT = true;

        public Database {
            tablePrefix = tablePrefix == null ? "" : tablePrefix;
            if (maxPoolSize <= 0) {
                maxPoolSize = DEFAULT_MAX_POOL_SIZE;
            }
        }

        /** @throws ConfigurationException when the URL is missing or not a JDBC URL */
        public void validate() throws ConfigurationException {
            if (url == null || url.isBlank()) {
                throw new ConfigurationException("data.storage.db.url is required for DB storage");
            }
            if (!url.startsWith("jdbc:")) {
                throw new ConfigurationException("Invalid data.storage.db.url: " + url);
            }
        }
    }

    public enum BinaryBackend { FILE, S3 }

    /** {@code data.binary.storage.*}; {@code s3} is validated only when {@code type == S3}. */
    public record BinaryStorage(BinaryBackend type, boolean fallback, File fileFolder, S3 s3) {
        public BinaryStorage {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(fileFolder, "fileFolder");
        }
    }

    /** {@code data.binary.storage.s3.*}. */
    public record S3(String bucket, String region, String accessKey, String secretKey,
                     String endpoint, String prefix, boolean pathStyleAccess) {
        public S3 {
            endpoint = endpoint == null ? "" : endpoint;
            prefix = prefix == null ? "" : prefix;
        }

        /** @throws ConfigurationException listing every missing required key */
        public void validate() throws ConfigurationException {
            List<String> missing = new ArrayList<>();
            if (isBlank(bucket)) missing.add("bucket");
            if (isBlank(region)) missing.add("region");
            if (isBlank(accessKey)) missing.add("access_key");
            if (isBlank(secretKey)) missing.add("secret_key");
            if (!missing.isEmpty()) {
                throw new ConfigurationException("S3 binary storage requires data.binary.storage.s3." + String.join(", ", missing));
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /**
     * Reads and validates the core settings.
     *
     * @param config  the loaded {@code config.yml}
     * @param baseDir the directory relative folders are resolved against
     * @throws ConfigurationException when the token is missing or the placeholder, a backend type is unknown, or
     *                                the selected DB/S3 backend lacks required keys
     */
    public static CoreSettings from(Configuration config, File baseDir) throws ConfigurationException {
        String token = config.getString("discord.token", "");
        if (token.isBlank() || token.equals(TOKEN_PLACEHOLDER)) {
            throw new ConfigurationException("Please set discord.token in config.yml");
        }

        Commands commands = new Commands(
                config.getString("commands.default-prefix", "!"),
                config.getBoolean("commands.system.help", true),
                config.getBoolean("commands.system.version", true),
                config.getBoolean("commands.system.shutdown", true),
                config.getBoolean("commands.system.perm", true));

        DataBackend dataBackend = backend(DataBackend.class, config.getString("data.storage.type", "FILE"), "data.storage.type");
        Database database = new Database(
                config.getString("data.storage.db.url", null),
                config.getString("data.storage.db.username", null),
                config.getString("data.storage.db.password", null),
                config.getString("data.storage.db.table_prefix", ""),
                config.getInt("data.storage.db.max_pool_size", Database.DEFAULT_MAX_POOL_SIZE),
                config.getBoolean("data.storage.db.auto_commit", Database.DEFAULT_AUTO_COMMIT));
        if (dataBackend == DataBackend.DB) {
            database.validate();
        }
        DataStorage dataStorage = new DataStorage(dataBackend,
                config.getBoolean("data.storage.fallback", false),
                resolve(baseDir, config.getString("data.storage.file.folder", "data")),
                config.getInt("data.storage.file.debounce_ms", 2000),
                database);

        BinaryBackend binaryBackend = backend(BinaryBackend.class, config.getString("data.binary.storage.type", "FILE"), "data.binary.storage.type");
        S3 s3 = new S3(
                config.getString("data.binary.storage.s3.bucket", null),
                config.getString("data.binary.storage.s3.region", null),
                config.getString("data.binary.storage.s3.access_key", null),
                config.getString("data.binary.storage.s3.secret_key", null),
                config.getString("data.binary.storage.s3.endpoint", ""),
                config.getString("data.binary.storage.s3.prefix", ""),
                config.getBoolean("data.binary.storage.s3.path_style_access", false));
        if (binaryBackend == BinaryBackend.S3) {
            s3.validate();
        }
        BinaryStorage binaryStorage = new BinaryStorage(binaryBackend,
                config.getBoolean("data.binary.storage.fallback", false),
                resolve(baseDir, config.getString("data.binary.storage.file.folder", "binary")),
                s3);

        return new CoreSettings(token, defaultLocale(config), commands,
                config.getStringList("permissions.operators", List.of()),
                dataStorage, binaryStorage, AudioSettings.fromConfig(config));
    }

    static Locale defaultLocale(Configuration config) {
        String tag = config.getString("language.default", "en-US");
        Locale locale = Locale.forLanguageTag(tag);
        if (locale.getLanguage().isEmpty()) {
            logger.warn("Invalid language.default '{}', falling back to en-US", tag);
            return Locale.US;
        }
        return locale;
    }

    private static <E extends Enum<E>> E backend(Class<E> type, String value, String key) throws ConfigurationException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Unknown " + key + " '" + value + "', expected one of "
                    + List.of(type.getEnumConstants()));
        }
    }

    private static File resolve(File baseDir, String folder) {
        File file = new File(folder);
        return file.isAbsolute() ? file : new File(baseDir, folder);
    }

}
