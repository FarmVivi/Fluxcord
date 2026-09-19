package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YamlConfigurationTest {

    private static File write(Path dir, String content) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    @Test
    void readsNestedValuesWithDottedKeys(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, """
                discord:
                  token: abc
                commands:
                  cooldown: 3
                  system:
                    help: true
                  aliases: [a, b]
                """));

        assertEquals("abc", config.getString("discord.token"));
        assertEquals(3, config.getInt("commands.cooldown"));
        assertTrue(config.getBoolean("commands.system.help"));
        assertEquals(List.of("a", "b"), config.getStringList("commands.aliases"));
        assertTrue(config.contains("commands.system"));
        assertFalse(config.contains("commands.nope"));
        assertEquals(List.of("commands", "discord"), config.getKeys().stream().sorted().toList());
    }

    @Test
    void missingKeyThrowsOrReturnsDefault(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, "a: 1\n"));

        assertThrows(ConfigurationException.class, () -> config.getString("b"));
        assertThrows(ConfigurationException.class, () -> config.getString("a.b"), "scalar used as a section");
        assertEquals("dflt", config.getString("b", "dflt"));
        assertEquals(7, config.getInt("b", 7));
        assertTrue(config.getBoolean("b", true));
        assertEquals(List.of("x"), config.getStringList("b", List.of("x")));
    }

    @Test
    void nullValueCountsAsMissing(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, "a:\nb: ~\n"));

        assertFalse(config.contains("a"));
        assertEquals("d", config.getString("b", "d"));
    }

    @Test
    void wrongTypesAreReported(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, "n: notanumber\nl: scalar\n"));

        assertThrows(ConfigurationException.class, () -> config.getInt("n"));
        assertEquals(5, config.getInt("n", 5));
        assertThrows(ConfigurationException.class, () -> config.getStringList("l"));
    }

    @Test
    void booleanParsingIsStrict(@TempDir Path dir) throws Exception {
        // Characterization: snakeyaml resolves YAML 1.1 booleans (yes/no/on/off) itself; any other
        // scalar goes through Boolean.parseBoolean, so "1" or a quoted "yes" are silently false.
        YamlConfiguration config = new YamlConfiguration(write(dir, "a: yes\nb: TRUE\nc: 1\nd: \"yes\"\n"));

        assertTrue(config.getBoolean("a"));
        assertTrue(config.getBoolean("b"));
        assertFalse(config.getBoolean("c"));
        assertFalse(config.getBoolean("d"));
    }

    @Test
    void setCreatesIntermediateSectionsAndSaveRoundTrips(@TempDir Path dir) throws Exception {
        File file = write(dir, "existing: 1\n");
        YamlConfiguration config = new YamlConfiguration(file);

        config.set("commands.default-prefix", "$");
        config.set("commands.system.help", false);
        config.set("", "ignored");
        config.save();

        YamlConfiguration reloaded = new YamlConfiguration(file);
        assertEquals(1, reloaded.getInt("existing"));
        assertEquals("$", reloaded.getString("commands.default-prefix"));
        assertFalse(reloaded.getBoolean("commands.system.help"));
    }

    @Test
    void setReplacesAScalarByASectionWhenNeeded(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, "a: scalar\n"));

        config.set("a.b", 2);

        assertEquals(2, config.getInt("a.b"));
        // Characterization: reading a section as a string does not fail, it returns Map.toString().
        assertEquals("{b=2}", config.getString("a"));
    }

    @Test
    void saveDropsComments(@TempDir Path dir) throws Exception {
        // Characterization of a known limitation (docs/refactoring-plan.md, hygiene): snakeyaml dump
        // rewrites the file without comments. If this test starts failing, the limitation is gone —
        // update the plan.
        File file = write(dir, "# keep me\na: 1 # inline\n");
        YamlConfiguration config = new YamlConfiguration(file);

        config.save();

        String content = Files.readString(file.toPath());
        assertFalse(content.contains("keep me"));
        assertFalse(content.contains("inline"));
    }

    @Test
    void reloadHandlesMissingAndEmptyFiles(@TempDir Path dir) throws Exception {
        File missing = dir.resolve("missing.yml").toFile();
        YamlConfiguration config = new YamlConfiguration(missing);
        assertTrue(config.getKeys().isEmpty());
        assertThrows(ConfigurationException.class, () -> config.getString("x"));

        File empty = write(dir, "");
        assertTrue(new YamlConfiguration(empty).getKeys().isEmpty());

        File list = write(dir, "- not\n- a map\n");
        assertTrue(new YamlConfiguration(list).getKeys().isEmpty(), "non-map root is ignored");
    }

    @Test
    void saveWithoutFileFails() {
        YamlConfiguration config = new YamlConfiguration() {
        };
        assertThrows(ConfigurationException.class, config::save);
    }

    @Test
    void getValuesReturnsACopyOfTheRootOnly(@TempDir Path dir) throws Exception {
        YamlConfiguration config = new YamlConfiguration(write(dir, "a: 1\n"));

        config.getValues().put("b", 2);

        assertFalse(config.contains("b"));
    }
}
