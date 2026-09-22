package com.example.plugin;

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
 * The plugin asks for keys like {@code lifecycle.plugin_enabled}; the core flattens the YAML into
 * dotted keys and hands back the key itself when it is missing. A wrong nesting level is therefore
 * invisible until someone reads the key instead of the message — which is what this template used
 * to do, its file being wrapped in a top-level {@code template:} block.
 *
 * <p>Copying this test along with the template is the point: it costs nothing and catches the most
 * common i18n mistake.
 */
class LanguageFilesTest {

    /** Every key the plugin looks up, as spelled in the source. */
    private static final List<String> USED_KEYS = List.of(
            "lifecycle.plugin_enabled", "lifecycle.plugin_disabled",
            "commands.example",
            "messages.example_message", "messages.mention_response",
            "errors.no_permission");

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
}
