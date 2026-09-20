package fr.farmvivi.fluxcord.core.storage.db;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.storage.AbstractDataStorage;
import fr.farmvivi.fluxcord.core.storage.StorageJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

import java.sql.*;
import java.util.*;

/**
 * Database implementation of DataStorage using HikariCP.
 * Stores data in a relational database with a simple key-value schema.
 */
public class DatabaseDataStorage extends AbstractDataStorage {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseDataStorage.class);
    private static final Gson gson = StorageJson.compact();

    private static final String BASE_TABLE_NAME = "storage_data";

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final String tableName;
    private final String indexName;
    private final Statements sql;

    /**
     * Every statement, built once from the validated table name. The name cannot be a bound parameter (SQL
     * identifiers are not values), so it is checked against {@code [A-Za-z0-9_]+} by {@link #sanitizeTablePrefix}
     * and interpolated here and nowhere else.
     */
    record Statements(String createTable, String createIndex, String select, String upsert, String exists,
                      String delete, String keys, String all, String clear) {
        private static final String BY_SCOPE = " WHERE scope = ?";
        private static final String BY_KEY = BY_SCOPE + " AND key_name = ?";

        static Statements of(SqlDialect dialect, String table, String index) {
            return new Statements(
                    "CREATE TABLE IF NOT EXISTS " + table + " (scope VARCHAR(255) NOT NULL, key_name VARCHAR(255) NOT NULL, "
                            + "value_data " + dialect.textColumnType() + ", PRIMARY KEY (scope, key_name))",
                    "CREATE INDEX IF NOT EXISTS " + index + " ON " + table + " (scope)",
                    "SELECT value_data FROM " + table + BY_KEY,
                    dialect.upsertStatement(table),
                    "SELECT 1 FROM " + table + BY_KEY,
                    "DELETE FROM " + table + BY_KEY,
                    "SELECT key_name FROM " + table + BY_SCOPE,
                    "SELECT key_name, value_data FROM " + table + BY_SCOPE,
                    "DELETE FROM " + table + BY_SCOPE);
        }
    }

    /**
     * Creates a new database data storage (HikariCP pool, schema created if missing).
     *
     * @param settings     validated {@code data.storage.db.*} settings
     * @param eventManager the event manager
     * @throws RuntimeException when the pool or the schema cannot be initialised
     */
    public DatabaseDataStorage(CoreSettings.Database settings, EventManager eventManager) {
        super("database", eventManager);
        // The JDBC URL determines both the connection and the SQL dialect
        this.dialect = SqlDialect.fromJdbcUrl(settings.url());
        // Optional table prefix so several bots can share the same database/schema
        // (e.g. prefix "bot1_" -> table "bot1_storage_data"). Empty by default.
        this.tableName = sanitizeTablePrefix(settings.tablePrefix()) + BASE_TABLE_NAME;
        this.indexName = "idx_" + tableName + "_scope";
        this.sql = Statements.of(dialect, tableName, indexName);
        this.dataSource = initializeDataSource(settings);
        initializeSchema();
    }

    /**
     * Storage over an existing pool with an explicit dialect (tests, embedded databases). {@link #close()} closes
     * the pool only when it is a {@link HikariDataSource}.
     */
    DatabaseDataStorage(DataSource dataSource, SqlDialect dialect, String tablePrefix, EventManager eventManager) {
        super("database", eventManager);
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.tableName = sanitizeTablePrefix(tablePrefix) + BASE_TABLE_NAME;
        this.indexName = "idx_" + tableName + "_scope";
        this.sql = Statements.of(dialect, tableName, indexName);
        initializeSchema();
    }

    /**
     * Validates the configured table prefix. Because a table name cannot be passed as a
     * bound parameter, the prefix is concatenated directly into SQL; we therefore restrict
     * it to a safe identifier charset to prevent SQL injection.
     *
     * @param prefix the raw prefix from configuration (may be empty)
     * @return the prefix unchanged if valid
     * @throws IllegalArgumentException if the prefix contains anything other than letters,
     *                                  digits or underscores
     */
    private static String sanitizeTablePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        if (!prefix.matches("\\w+")) {
            throw new IllegalArgumentException("Invalid data.storage.db.table_prefix '" + prefix
                    + "': only letters, digits and underscores are allowed");
        }
        return prefix;
    }

    /** Initializes the HikariCP pool from the settings; unset optional values keep sensible defaults. */
    private HikariDataSource initializeDataSource(CoreSettings.Database settings) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(settings.url());
        hikariConfig.setUsername(settings.username());
        hikariConfig.setPassword(settings.password());

        hikariConfig.setMaximumPoolSize(settings.maxPoolSize() != null ? settings.maxPoolSize() : 10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setIdleTimeout(30000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setAutoCommit(settings.autoCommit() == null || settings.autoCommit());

        logger.info("Initializing {} database connection pool to {}", dialect, settings.url());
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Creates the necessary database schema if it doesn't exist.
     */
    private void initializeSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create the data table (value column type depends on the SQL dialect)
            stmt.execute(sql.createTable());
            stmt.execute(sql.createIndex());

            logger.info("Database schema initialized successfully");
        } catch (SQLException e) {
            logger.error("Error initializing database schema", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    // Implementation des méthodes abstraites d'AbstractDataStorage

    @Override
    protected <T> Optional<T> doGet(StorageKey key, Class<T> type) {
        String scope = key.getScope();
        String keyName = key.getKey();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.select())) {

            stmt.setString(1, scope);
            stmt.setString(2, keyName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("value_data");
                    T value = gson.fromJson(json, type);
                    return Optional.ofNullable(value);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting data for key {} in scope {}: {}",
                    keyName, scope, e.getMessage());
        }

        return Optional.empty();
    }

    @Override
    protected <T> boolean doSet(StorageKey key, T value) {
        String scope = key.getScope();
        String keyName = key.getKey();
        String json = gson.toJson(value);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.upsert())) {

            stmt.setString(1, scope);
            stmt.setString(2, keyName);
            stmt.setString(3, json);

            int updated = stmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            logger.error("Error setting data for key {} in scope {}: {}",
                    keyName, scope, e.getMessage());
            return false;
        }
    }

    @Override
    protected boolean doExists(StorageKey key) {
        String scope = key.getScope();
        String keyName = key.getKey();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.exists())) {

            stmt.setString(1, scope);
            stmt.setString(2, keyName);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Error checking if key {} exists in scope {}: {}",
                    keyName, scope, e.getMessage());
            return false;
        }
    }

    @Override
    protected boolean doRemove(StorageKey key) {
        String scope = key.getScope();
        String keyName = key.getKey();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.delete())) {

            stmt.setString(1, scope);
            stmt.setString(2, keyName);

            int deleted = stmt.executeUpdate();
            return deleted > 0;
        } catch (SQLException e) {
            logger.error("Error removing key {} from scope {}: {}",
                    keyName, scope, e.getMessage());
            return false;
        }
    }

    @Override
    protected Set<String> doGetKeys(String scope) {
        Set<String> keys = new HashSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.keys())) {

            stmt.setString(1, scope);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("key_name"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting keys for scope {}: {}", scope, e.getMessage());
        }

        return keys;
    }

    @Override
    protected Map<String, Object> doGetAll(String scope) {
        Map<String, Object> data = new HashMap<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.all())) {

            stmt.setString(1, scope);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String keyName = rs.getString("key_name");
                    String json = rs.getString("value_data");
                    Object value = gson.fromJson(json, Object.class);
                    data.put(keyName, value);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting all data for scope {}: {}", scope, e.getMessage());
        }

        return data;
    }

    @Override
    protected boolean doClear(String scope) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     sql.clear())) {

            stmt.setString(1, scope);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.error("Error clearing scope {}: {}", scope, e.getMessage());
            return false;
        }
    }

    /** The pool behind this storage (tests inspect stored rows through it). */
    DataSource dataSource() {
        return dataSource;
    }

    @Override
    public boolean close() {
        if (dataSource instanceof HikariDataSource pool && !pool.isClosed()) {
            pool.close();
            logger.info("Database connection pool closed");
        }
        return true;
    }
}