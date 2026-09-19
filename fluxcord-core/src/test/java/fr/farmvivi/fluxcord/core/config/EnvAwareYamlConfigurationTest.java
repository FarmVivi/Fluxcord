package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import fr.farmvivi.fluxcord.core.util.EnvironmentUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvAwareYamlConfigurationTest {

    /** Same class, with the environment replaced by a map. */
    private static class FakeEnvConfiguration extends EnvAwareYamlConfiguration {
        private final Map<String, String> env = new HashMap<>();

        FakeEnvConfiguration(File file) throws ConfigurationException {
            super(file);
        }

        FakeEnvConfiguration with(String key, String value) {
            env.put(key, value);
            return this;
        }

        @Override
        protected String lookupEnv(String key) {
            return env.get(key);
        }
    }

    private static FakeEnvConfiguration config(Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                discord:
                  token: from-file
                commands:
                  cooldown: 3
                  system:
                    help: true
                  aliases: [a, b]
                """);
        return new FakeEnvConfiguration(file.toFile());
    }

    @Test
    void environmentOverridesFileValues(@TempDir Path dir) throws Exception {
        FakeEnvConfiguration config = config(dir)
                .with("discord.token", "from-env")
                .with("commands.cooldown", "10")
                .with("commands.system.help", "false")
                .with("commands.aliases", "x:y");

        assertEquals("from-env", config.getString("discord.token"));
        assertEquals("from-env", config.getString("discord.token", "d"));
        assertEquals(10, config.getInt("commands.cooldown"));
        assertEquals(10, config.getInt("commands.cooldown", 0));
        assertFalse(config.getBoolean("commands.system.help"));
        assertFalse(config.getBoolean("commands.system.help", true));
        assertEquals(List.of("x", "y"), config.getStringList("commands.aliases"));
        assertEquals(List.of("x", "y"), config.getStringList("commands.aliases", List.of()));
    }

    @Test
    void fileValuesAreUsedWhenNoEnvironmentValue(@TempDir Path dir) throws Exception {
        FakeEnvConfiguration config = config(dir);

        assertEquals("from-file", config.getString("discord.token"));
        assertEquals(3, config.getInt("commands.cooldown"));
        assertTrue(config.getBoolean("commands.system.help"));
        assertEquals(List.of("a", "b"), config.getStringList("commands.aliases"));
    }

    @Test
    void environmentCanProvideKeysAbsentFromTheFile(@TempDir Path dir) throws Exception {
        FakeEnvConfiguration config = config(dir).with("extra.key", "v");

        assertEquals("v", config.getString("extra.key"));
        assertThrows(ConfigurationException.class, () -> config.getString("other.key"));
    }

    @Test
    void nonNumericEnvironmentValueThrowsOrFallsBackToDefault(@TempDir Path dir) throws Exception {
        FakeEnvConfiguration config = config(dir).with("commands.cooldown", "many");

        assertThrows(ConfigurationException.class, () -> config.getInt("commands.cooldown"));
        // Characterization: the env value wins over the file even when unparsable — the default is used,
        // not the file value 3.
        assertEquals(42, config.getInt("commands.cooldown", 42));
    }

    @Test
    void environmentDoesNotLeakIntoSavedFile(@TempDir Path dir) throws Exception {
        FakeEnvConfiguration config = config(dir).with("discord.token", "from-env");

        config.save();

        String saved = Files.readString(dir.resolve("config.yml"));
        assertTrue(saved.contains("from-file"));
        assertFalse(saved.contains("from-env"));
    }

    @Test
    void environmentKeyMapping() {
        // FLUXCORD_ prefix, dots to underscores, upper case. Reads the real environment: the key must be absent.
        assertNull(EnvironmentUtils.getEnv("some.key.that.nobody.sets"));
        assertEquals(0, EnvironmentUtils.getEnvValues("some.key.that.nobody.sets").length);
        // PATH is set everywhere but not with the prefix, so the prefix must be applied.
        assertNull(EnvironmentUtils.getEnv("path"), "lookup must go through the FLUXCORD_ prefix");
    }
}
