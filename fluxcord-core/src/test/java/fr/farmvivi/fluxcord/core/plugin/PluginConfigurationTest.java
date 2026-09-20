package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PluginConfiguration versioning and default config copying functionality.
 */
class PluginConfigurationTest {

    @TempDir
    Path tempDir;

    @Mock
    private PluginClassLoader mockClassLoader;

    private String pluginName = "TestPlugin";
    private File pluginFolder;
    private File configFile;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Set up temporary plugin structure
        System.setProperty("plugins.dir", tempDir.resolve("plugins").toString());
        pluginFolder = tempDir.resolve("plugins").resolve(pluginName).toFile();
        configFile = new File(pluginFolder, "config.yml");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("plugins.dir");
    }

    @Test
    void testDefaultConfigCopyingFromJAR() throws ConfigurationException {
        // Given: no existing config file, but JAR contains default config
        String defaultConfig = """
                config_version: 1
                plugin_settings:
                  enabled: true
                  max_items: 100
                """;

        InputStream configStream = new ByteArrayInputStream(defaultConfig.getBytes());
        when(mockClassLoader.getResourceAsStream("config.yml")).thenReturn(configStream);

        // When: creating plugin configuration
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // Then: config file is copied from JAR
        assertTrue(configFile.exists());
        assertEquals(1, config.getConfigVersion());
        assertTrue(config.getBoolean("plugin_settings.enabled"));
        assertEquals(100, config.getInt("plugin_settings.max_items"));

        verify(mockClassLoader).getResourceAsStream("config.yml");
    }

    @Test
    void testExistingConfigIsNotOverwritten() throws ConfigurationException, IOException {
        // Given: existing config file with custom settings
        pluginFolder.mkdirs();
        String existingConfig = """
                config_version: 1
                plugin_settings:
                  enabled: false
                  max_items: 200
                  custom_setting: "user_value"
                """;
        Files.writeString(configFile.toPath(), existingConfig);

        // And: JAR contains different default config
        String defaultConfig = """
                config_version: 1
                plugin_settings:
                  enabled: true
                  max_items: 100
                """;
        InputStream configStream = new ByteArrayInputStream(defaultConfig.getBytes());
        when(mockClassLoader.getResourceAsStream("config.yml")).thenReturn(configStream);

        // When: creating plugin configuration
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // Then: existing config is preserved
        assertFalse(config.getBoolean("plugin_settings.enabled")); // User's setting
        assertEquals(200, config.getInt("plugin_settings.max_items")); // User's setting
        assertEquals("user_value", config.getString("plugin_settings.custom_setting"));

        // And: default config is not copied
        verify(mockClassLoader, never()).getResourceAsStream("config.yml");
    }

    @Test
    void testLegacyConfigMigration() throws ConfigurationException, IOException {
        // Given: existing config without version
        pluginFolder.mkdirs();
        String legacyConfig = """
                plugin_settings:
                  enabled: true
                  max_items: 150
                """;
        Files.writeString(configFile.toPath(), legacyConfig);

        // When: creating plugin configuration
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // Then: version is added
        assertEquals(1, config.getConfigVersion());
        assertTrue(config.getBoolean("plugin_settings.enabled"));
        assertEquals(150, config.getInt("plugin_settings.max_items"));

        // And: backup is created
        File[] backupFiles = pluginFolder.listFiles((dir, name) ->
                name.startsWith("config.yml.backup."));
        assertNotNull(backupFiles);
        assertTrue(backupFiles.length > 0);
    }

    @Test
    void testNoDefaultConfigInJAR() throws ConfigurationException {
        // Given: no existing config file and no default in JAR
        when(mockClassLoader.getResourceAsStream("config.yml")).thenReturn(null);

        // When: creating plugin configuration
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // Then: empty configuration is created
        assertFalse(configFile.exists()); // No file created if no default
        assertEquals(0, config.getConfigVersion()); // No version if no config

        verify(mockClassLoader).getResourceAsStream("config.yml");
    }

    @Test
    void testDataFolderPath() throws ConfigurationException {
        // Given: plugin configuration
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // When: getting data folder path
        String dataFolder = config.getPluginDataFolder();

        // Then: correct path is returned
        assertTrue(dataFolder.endsWith("plugins" + File.separator + pluginName));
        assertTrue(new File(dataFolder).exists());
    }

    @Test
    void testConfigurationPersistence() throws ConfigurationException {
        // Given: plugin configuration with default values
        String defaultConfig = """
                config_version: 1
                test_setting: "initial_value"
                """;
        InputStream configStream = new ByteArrayInputStream(defaultConfig.getBytes());
        when(mockClassLoader.getResourceAsStream("config.yml")).thenReturn(configStream);

        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // When: modifying and saving configuration
        config.set("test_setting", "modified_value");
        config.set("new_setting", "new_value");
        config.save();

        // Then: changes are persisted
        PluginConfiguration reloadedConfig = new PluginConfiguration(pluginName, null);
        assertEquals("modified_value", reloadedConfig.getString("test_setting"));
        assertEquals("new_value", reloadedConfig.getString("new_setting"));
        assertEquals(1, reloadedConfig.getConfigVersion());
    }

    @Test
    void testConfigurationDefaults() throws ConfigurationException {
        // Given: minimal default config
        String defaultConfig = """
                config_version: 1
                basic_setting: "default"
                """;
        InputStream configStream = new ByteArrayInputStream(defaultConfig.getBytes());
        when(mockClassLoader.getResourceAsStream("config.yml")).thenReturn(configStream);

        // When: creating configuration and accessing values with defaults
        PluginConfiguration config = new PluginConfiguration(pluginName, mockClassLoader);

        // Then: defaults work correctly
        assertEquals("default", config.getString("basic_setting"));
        assertEquals("fallback", config.getString("missing_setting", "fallback"));
        assertEquals(42, config.getInt("missing_int", 42));
        assertFalse(config.getBoolean("missing_bool", false));
    }

    @Test
    void testFolderCreation() throws ConfigurationException {
        // Given: no existing plugin folder
        assertFalse(pluginFolder.exists());

        // When: creating plugin configuration
        new PluginConfiguration(pluginName, mockClassLoader);

        // Then: plugin folder is created
        assertTrue(pluginFolder.exists());
        assertTrue(pluginFolder.isDirectory());
    }

    // ---- plugin-driven migration (config_version + ConfigurableMigrationPlugin) --------------------------------

    /** A migrator that bumps a key; {@code fail} makes it throw, {@code invalid} makes validation fail. */
    public static class Migrator implements fr.farmvivi.fluxcord.api.plugin.ConfigurableMigrationPlugin {
        static int expected = 3;
        static boolean fail, invalid;
        static java.util.List<String> calls = new java.util.ArrayList<>();

        @Override public int getExpectedConfigVersion() { return expected; }

        @Override
        public void migrateConfiguration(fr.farmvivi.fluxcord.api.config.Configuration config, int from, int to)
                throws fr.farmvivi.fluxcord.api.config.ConfigurationException {
            calls.add(from + "->" + to);
            if (fail) throw new fr.farmvivi.fluxcord.api.config.ConfigurationException("boom");
            config.set("migrated", true);
        }

        @Override
        public void validateConfiguration(fr.farmvivi.fluxcord.api.config.Configuration config)
                throws fr.farmvivi.fluxcord.api.config.ConfigurationException {
            calls.add("validate");
            if (invalid) throw new fr.farmvivi.fluxcord.api.config.ConfigurationException("invalid");
        }
    }

    private fr.farmvivi.fluxcord.api.plugin.Plugin pluginWith(Class<? extends fr.farmvivi.fluxcord.api.plugin.ConfigurableMigrationPlugin> migration) {
        fr.farmvivi.fluxcord.api.plugin.Plugin plugin = mock(fr.farmvivi.fluxcord.api.plugin.Plugin.class);
        when(plugin.getId()).thenReturn(pluginName);
        when(plugin.getName()).thenReturn(pluginName);
        doReturn(migration).when(plugin).getMigrationClass();
        return plugin;
    }

    private PluginConfiguration configWithVersion(int version) throws Exception {
        pluginFolder.mkdirs();
        java.nio.file.Files.writeString(configFile.toPath(), "config_version: " + version + "\nkeep: sure\n");
        Migrator.calls.clear();
        Migrator.fail = false;
        Migrator.invalid = false;
        Migrator.expected = 3;
        return new PluginConfiguration(pluginName, mockClassLoader);
    }

    @Test
    void migrationClassRunsWhenTheFileIsOlderAndBacksUpFirst() throws Exception {
        PluginConfiguration config = configWithVersion(1);

        config.initializeMigration(pluginWith(Migrator.class));

        assertEquals(java.util.List.of("1->3", "validate"), Migrator.calls);
        assertEquals(3, config.getInt("config_version"));
        assertTrue(config.getBoolean("migrated"));
        assertEquals("sure", config.getString("keep"), "other keys survive");
        PluginConfiguration reloaded = new PluginConfiguration(pluginName, mockClassLoader);
        assertEquals(3, reloaded.getInt("config_version"), "persisted");
        assertTrue(java.util.Arrays.stream(pluginFolder.listFiles()).anyMatch(f -> f.getName().startsWith("config.yml.backup.")), "backup taken before migrating");
    }

    @Test
    void upToDateOrNewerConfigOnlyGetsValidated() throws Exception {
        PluginConfiguration config = configWithVersion(3);
        config.initializeMigration(pluginWith(Migrator.class));
        assertEquals(java.util.List.of("validate"), Migrator.calls);

        PluginConfiguration newer = configWithVersion(9);
        newer.initializeMigration(pluginWith(Migrator.class));
        assertEquals(java.util.List.of("validate"), Migrator.calls, "newer than expected: warned, not touched");
        assertEquals(9, newer.getInt("config_version"));
    }

    @Test
    void aFailingMigrationOrValidationIsLoggedNotFatal() throws Exception {
        PluginConfiguration config = configWithVersion(1);
        Migrator.fail = true;
        assertDoesNotThrow(() -> config.initializeMigration(pluginWith(Migrator.class)));
        assertEquals(1, config.getInt("config_version"), "version untouched after a failed migration");

        PluginConfiguration other = configWithVersion(1);
        Migrator.invalid = true;
        assertDoesNotThrow(() -> other.initializeMigration(pluginWith(Migrator.class)));
        assertEquals(3, other.getInt("config_version"), "migration succeeded, only validation complained");
    }

    @Test
    void thePluginItselfCanBeTheMigratorAndAnUninstantiableClassFallsBackToIt() throws Exception {
        PluginConfiguration config = configWithVersion(2);
        fr.farmvivi.fluxcord.api.plugin.Plugin migratingPlugin = mock(fr.farmvivi.fluxcord.api.plugin.Plugin.class,
                withSettings().extraInterfaces(fr.farmvivi.fluxcord.api.plugin.ConfigurableMigrationPlugin.class));
        when(migratingPlugin.getId()).thenReturn(pluginName);
        when(migratingPlugin.getName()).thenReturn(pluginName);
        when(((fr.farmvivi.fluxcord.api.plugin.ConfigurableMigrationPlugin) migratingPlugin).getExpectedConfigVersion()).thenReturn(5);

        config.initializeMigration(migratingPlugin);

        verify((fr.farmvivi.fluxcord.api.plugin.ConfigurableMigrationPlugin) migratingPlugin).migrateConfiguration(config, 2, 5);
        assertEquals(5, config.getInt("config_version"));

        fr.farmvivi.fluxcord.api.plugin.Plugin plain = pluginWith(null);
        assertDoesNotThrow(() -> configWithVersion(1).initializeMigration(plain), "no migration support at all: nothing happens");
    }
}
