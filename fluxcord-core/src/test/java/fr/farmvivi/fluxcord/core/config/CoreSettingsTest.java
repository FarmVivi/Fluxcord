package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Plan item B3: the core reads {@code config.yml} once, into validated records. */
class CoreSettingsTest {

    @TempDir Path dir;

    private CoreConfiguration config() throws ConfigurationException {
        CoreConfiguration config = new CoreConfiguration(dir.resolve("config.yml").toFile());
        config.set("discord.token", "abc");
        return config;
    }

    @Test
    void defaultConfigWithATokenParsesToTheDocumentedDefaults() throws Exception {
        CoreSettings s = CoreSettings.from(config(), dir.toFile());

        assertEquals("abc", s.token());
        assertEquals(Locale.US, s.defaultLocale());
        assertEquals(new CoreSettings.Commands("!", true, true, true, true), s.commands());
        assertEquals(List.of(), s.operators());
        assertEquals(CoreSettings.DataBackend.FILE, s.dataStorage().type());
        assertFalse(s.dataStorage().fallback());
        assertEquals(new File(dir.toFile(), "data"), s.dataStorage().fileFolder(), "relative folders resolve against baseDir");
        assertEquals(2000, s.dataStorage().debounceMs());
        assertEquals(CoreSettings.BinaryBackend.FILE, s.binaryStorage().type());
        assertEquals(new File(dir.toFile(), "binary"), s.binaryStorage().fileFolder());
        assertEquals(200, s.audio().fadeDurationMs());
        assertEquals(20, s.audio().duckingLevel());
    }

    @Test
    void placeholderOrMissingTokenIsRejected() throws Exception {
        CoreConfiguration config = new CoreConfiguration(dir.resolve("config.yml").toFile());
        assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()), "placeholder");
        config.set("discord.token", "  ");
        assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()), "blank");
    }

    @Test
    void invalidLanguageFallsBackToEnglishAndUnknownBackendsAreRejected() throws Exception {
        CoreConfiguration config = config();
        config.set("language.default", "");
        assertEquals(Locale.US, CoreSettings.from(config, dir.toFile()).defaultLocale());
        config.set("language.default", "fr-FR");
        assertEquals(Locale.FRANCE, CoreSettings.from(config, dir.toFile()).defaultLocale());

        config.set("data.storage.type", "redis");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()));
        assertTrue(e.getMessage().contains("data.storage.type") && e.getMessage().contains("FILE"), e.getMessage());
        config.set("data.storage.type", "file"); // case-insensitive
        config.set("data.binary.storage.type", "ftp");
        assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()));
    }

    @Test
    void databaseSettingsAreValidatedOnlyWhenTheDbBackendIsSelected() throws Exception {
        CoreConfiguration config = config();
        config.set("data.storage.db.url", "not-a-jdbc-url");
        assertDoesNotThrow(() -> CoreSettings.from(config, dir.toFile()), "FILE backend ignores db.*");

        config.set("data.storage.type", "DB");
        assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()));

        config.set("data.storage.db.url", "jdbc:postgresql://db/fluxcord");
        config.set("data.storage.db.table_prefix", "bot1_");
        config.set("data.storage.db.max_pool_size", 4);
        CoreSettings.Database db = CoreSettings.from(config, dir.toFile()).dataStorage().database();
        assertEquals("jdbc:postgresql://db/fluxcord", db.url());
        assertEquals("username", db.username(), "default config value");
        assertEquals("bot1_", db.tablePrefix());
        assertEquals(4, db.maxPoolSize());
        assertTrue(db.autoCommit(), "unset falls back to the documented default rather than null");
        // The pool size is refused rather than passed on when it makes no sense.
        config.set("data.storage.db.max_pool_size", 0);
        assertEquals(CoreSettings.Database.DEFAULT_MAX_POOL_SIZE,
                CoreSettings.from(config, dir.toFile()).dataStorage().database().maxPoolSize());
    }

    @Test
    void s3SettingsListEveryMissingKey() throws Exception {
        CoreConfiguration config = config();
        config.set("data.binary.storage.type", "S3");
        config.set("data.binary.storage.s3.bucket", "");
        config.set("data.binary.storage.s3.secret_key", "");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> CoreSettings.from(config, dir.toFile()));
        assertTrue(e.getMessage().contains("bucket") && e.getMessage().contains("secret_key"), e.getMessage());
        assertFalse(e.getMessage().contains("region"), "region is set in the default config");

        config.set("data.binary.storage.s3.bucket", "b");
        config.set("data.binary.storage.s3.secret_key", "s");
        config.set("data.binary.storage.s3.path_style_access", true);
        CoreSettings.S3 s3 = CoreSettings.from(config, dir.toFile()).binaryStorage().s3();
        assertEquals("b", s3.bucket());
        assertTrue(s3.pathStyleAccess());
        assertEquals("fluxcord", s3.prefix());
    }

    @Test
    void absoluteFoldersAreKeptAndOperatorsAreRead() throws Exception {
        CoreConfiguration config = config();
        File absolute = dir.resolve("elsewhere").toFile();
        config.set("data.storage.file.folder", absolute.getAbsolutePath());
        config.set("permissions.operators", List.of("1", "2"));
        CoreSettings s = CoreSettings.from(config, dir.toFile());
        assertEquals(absolute, s.dataStorage().fileFolder());
        assertEquals(List.of("1", "2"), s.operators());
    }
}
