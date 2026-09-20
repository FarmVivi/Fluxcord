package fr.farmvivi.fluxcord.core.language;

import fr.farmvivi.fluxcord.core.testing.PluginJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Plan item L1: one loader for every language source (bundled resource, runtime folder, plugin jar). */
class LanguageFilesTest {

    @Test
    void yamlIsFlattenedToDottedKeysAndLeavesBecomeStrings() {
        Map<String, String> flat = LanguageFiles.parse(new StringReader("""
                commands:
                  messages:
                    cooldown: "Wait {0}s"
                  count: 12
                  enabled: true
                  nothing:
                top: hello
                """));
        assertEquals(Map.of("commands.messages.cooldown", "Wait {0}s", "commands.count", "12",
                "commands.enabled", "true", "top", "hello"), flat, "null leaves are skipped");
        assertTrue(LanguageFiles.parse(new StringReader("")).isEmpty());
        assertTrue(LanguageFiles.parse(new StringReader("- a list")).isEmpty(), "not a mapping: nothing");
    }

    @Test
    void localeComesFromTheFileName() {
        assertEquals(Locale.FRANCE, LanguageFiles.localeOf("fr-FR.yml"));
        assertEquals(Locale.FRANCE, LanguageFiles.localeOf("fr_FR.yml"));
        assertEquals(Locale.forLanguageTag("de"), LanguageFiles.localeOf("de"));
    }

    @Test
    void folderAndJarLoadIntoTheNamespaceInOrder(@TempDir Path dir) throws Exception {
        SimpleLanguageManager manager = new SimpleLanguageManager(Locale.US);
        manager.registerNamespace("alpha");

        File jar = PluginJars.build(dir, "alpha.jar", List.of(), Map.of(
                "lang/en-US.yml", "greet: Hello\nbye: Bye\n",
                "lang/fr-FR.yml", "greet: Bonjour\n",
                "lang/sub/ignored.yml", "greet: nope\n",
                "other/en-US.yml", "greet: nope\n"));
        try (JarFile jarFile = new JarFile(jar)) {
            assertEquals(3, LanguageFiles.loadJar(manager, "alpha", jarFile));
        }
        Path folder = dir.resolve("lang");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("en-US.yml"), "greet: Hi there\n");
        Files.writeString(folder.resolve("notes.txt"), "ignored");
        assertEquals(1, LanguageFiles.loadFolder(manager, "alpha", folder.toFile()));
        assertEquals(0, LanguageFiles.loadFolder(manager, "alpha", dir.resolve("missing").toFile()));

        assertEquals("Hi there", manager.getString(Locale.US, "alpha:greet"), "runtime folder overrides the jar");
        assertEquals("Bye", manager.getString(Locale.US, "alpha:bye"), "keys the override does not mention survive");
        assertEquals("Bonjour", manager.getString(Locale.FRANCE, "alpha:greet"));
        assertEquals("Bye", manager.getString(Locale.FRANCE, "alpha:bye"), "en-US fallback");
    }

    @Test
    void aBrokenFileDoesNotStopTheOthers(@TempDir Path dir) throws Exception {
        SimpleLanguageManager manager = new SimpleLanguageManager(Locale.US);
        Files.writeString(dir.resolve("en-US.yml"), "a: 1\n");
        Files.writeString(dir.resolve("fr-FR.yml"), "a: [unclosed\n");
        assertEquals(1, LanguageFiles.loadFolder(manager, "core", dir.toFile()));
        assertEquals("1", manager.getString(Locale.US, "a"));
    }
}
