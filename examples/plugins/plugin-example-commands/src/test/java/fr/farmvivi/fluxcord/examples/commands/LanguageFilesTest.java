package fr.farmvivi.fluxcord.examples.commands;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin asks for keys like {@code ping.response}; the core flattens the YAML into dotted keys
 * and hands back the key itself when it is missing. A wrong nesting level is therefore invisible
 * until someone reads "ping.response" in Discord — which is exactly what this example used to do,
 * its file being wrapped in a top-level {@code commands:} block.
 */
class LanguageFilesTest {

    /** Every key the plugin looks up, as spelled in the source. */
    private static final List<String> USED_KEYS = List.of(
            "ping.description", "ping.response",
            "echo.description", "echo.response", "echo.no_message", "echo.too_long",
            "info.description", "info.title", "info.version", "info.uptime",
            "info.commands_executed", "info.memory_usage",
            "admin.description", "admin.response",
            "status.enabled", "status.disabled", "status.ready", "status.commands_loaded");

    private Map<String, String> load(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            assertNotNull(in, "missing language file for " + locale);
            Map<String, Object> raw = new Yaml().load(in);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", raw, flat);
            return flat;
        } catch (Exception e) {
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

    @Test
    void everyKeyThePluginUsesExistsInEveryLocale() {
        for (String locale : List.of("en-US", "fr-FR")) {
            Map<String, String> translations = load(locale);
            for (String key : USED_KEYS) {
                assertTrue(translations.containsKey(key),
                        locale + " has no translation for '" + key + "' (a miss shows the key to the user)");
            }
        }
    }

    @Test
    void theTwoLocalesDeclareTheSameKeys() {
        Set<String> english = new TreeSet<>(load("en-US").keySet());
        Set<String> french = new TreeSet<>(load("fr-FR").keySet());

        assertEquals(english, french, "a key translated in one locale only falls back silently");
    }

    @Test
    void placeholdersMatchBetweenLocales() {
        Map<String, String> english = load("en-US");
        Map<String, String> french = load("fr-FR");

        english.forEach((key, value) -> assertEquals(placeholders(value), placeholders(french.get(key)),
                "'" + key + "' does not take the same arguments in both locales"));
    }

    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\d+}").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void everyKeyIsAStringAndNotSomethingYamlResolvedForUs() {
        // off/on/yes/no/true/false and bare numbers are not strings in YAML 1.1, which snakeyaml
        // implements: such a key is flattened through String.valueOf and stops matching what the code
        // asks for. It cost music-plugin a /loop choice labelled with a raw key.
        for (String locale : List.of("en-US", "fr-FR")) {
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

    /** The raw YAML tree, to inspect the key objects themselves rather than their string form. */
    private Map<String, Object> loadRaw(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            return new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }


    @Test
    void aFormattedStringNeverCarriesALoneApostrophe() {
        // MessageFormat swallows a single quote and the placeholders after it, so a value taking
        // arguments must double its apostrophes.
        for (String locale : List.of("en-US", "fr-FR")) {
            load(locale).forEach((key, value) -> {
                if (!placeholders(value).isEmpty()) {
                    assertFalse(value.replace("''", "").contains("'"),
                            locale + ": '" + key + "' takes arguments, so its apostrophes must be doubled");
                }
            });
        }
    }
}
