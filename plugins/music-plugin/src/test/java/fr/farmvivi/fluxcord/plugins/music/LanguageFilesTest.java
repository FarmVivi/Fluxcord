package fr.farmvivi.fluxcord.plugins.music;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The language files, against what the code actually looks up.
 *
 * <p>The module had no such test, and that is how {@code music.command.loop.mode.off} went unnoticed: the
 * key was there, correctly translated in both locales, and never resolved. In YAML 1.1 — which snakeyaml
 * implements — an unquoted {@code off} is the boolean {@code false}, so the key flattened to
 * {@code ...mode.false} and {@code /loop} offered its users a raw key as the label of a choice.
 */
class LanguageFilesTest {

    private static final List<String> LOCALES = List.of("en-US", "fr-FR");

    /**
     * A {@code music.*} literal handed to a translation call.
     *
     * <p>Only translation calls: the plugin also has {@code music.*} literals that are permission nodes
     * ({@code ButtonHandler} passes {@code "music.play"} to {@code hasPermission}, which re-prefixes it
     * with the plugin id). Matching every literal would report those as missing translations.
     */
    private static final Pattern KEY_LITERAL =
            Pattern.compile("(?:text|getString)\\s*\\([^()]{0,80}?\"(music\\.[A-Za-z0-9_.]+)\"");

    private Map<String, String> load(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            assertNotNull(in, "missing language file for " + locale);
            Map<String, Object> raw = new Yaml().load(in);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", raw, flat);
            return flat;
        } catch (IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> node, Map<String, String> into) {
        node.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                flatten(path, (Map<String, Object>) child, into);
            } else {
                into.put(path, String.valueOf(value));
            }
        });
    }

    /** The raw YAML tree, to inspect the key objects themselves rather than their string form. */
    private Map<String, Object> loadRaw(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }

    @Test
    void everyKeyIsAStringAndNotSomethingYamlResolvedForUs() {
        // off/on/yes/no/true/false and unquoted numbers are not strings in YAML 1.1: such a key is
        // flattened through String.valueOf and no longer matches what the code asks for.
        for (String locale : LOCALES) {
            assertNonStringKeys(locale, "", loadRaw(locale));
        }
    }

    private void assertNonStringKeys(String locale, String prefix, Map<?, ?> node) {
        node.forEach((key, value) -> {
            assertInstanceOf(String.class, key,
                    locale + ": the key '" + prefix + key + "' is a " + key.getClass().getSimpleName()
                            + ", not a string - quote it in the YAML");
            if (value instanceof Map<?, ?> child) {
                assertNonStringKeys(locale, prefix + key + ".", child);
            }
        });
    }

    @Test
    void everyKeyTheCodeNamesExistsInEveryLocale() {
        Set<String> used = keysUsedInSources();
        assertFalse(used.isEmpty(), "no key literal found; the scan is broken, not the translations");

        for (String locale : LOCALES) {
            Map<String, String> translations = load(locale);
            for (String key : used) {
                assertTrue(translations.containsKey(key),
                        locale + " has no translation for '" + key + "' (a miss shows the key to the user)");
            }
        }
    }

    /** Every whole key the sources hand to a translation call. */
    private Set<String> keysUsedInSources() {
        Path sources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sources), "run this test from the module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                Matcher matcher = KEY_LITERAL.matcher(read(path));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // "music.command." is completed at runtime with the command name; only whole keys count.
                    if (!key.endsWith(".")) {
                        keys.add(key);
                    }
                }
            });
        } catch (IOException e) {
            throw new AssertionError("cannot scan the sources", e);
        }
        return keys;
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    @Test
    void theTwoLocalesDeclareTheSameKeys() {
        assertEquals(new TreeSet<>(load("en-US").keySet()), new TreeSet<>(load("fr-FR").keySet()),
                "a key translated in one locale only falls back silently");
    }

    @Test
    void placeholdersMatchBetweenLocales() {
        Map<String, String> english = load("en-US");
        Map<String, String> french = load("fr-FR");

        english.forEach((key, value) -> assertEquals(placeholders(value), placeholders(french.get(key)),
                "'" + key + "' does not take the same arguments in both locales"));
    }

    @Test
    void aFormattedStringNeverCarriesALoneApostrophe() {
        // MessageFormat swallows a single quote and the placeholders after it. Only values holding a
        // placeholder are formatted, so only those are checked here.
        for (String locale : LOCALES) {
            load(locale).forEach((key, value) -> {
                if (!placeholders(value).isEmpty()) {
                    assertFalse(value.replace("''", "").contains("'"),
                            locale + ": '" + key + "' takes arguments, so its apostrophes must be doubled");
                }
            });
        }
    }

    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{\\d+}").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
