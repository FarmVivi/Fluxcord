package fr.farmvivi.fluxcord.plugins.aiaudio;

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
 * The plugin has no user-facing string yet, but its language files do — and they carried the same
 * redundant top-level wrapper ({@code aiaudio:}) that made every lookup miss in the other generated
 * plugins. Keeping the two locales in step now means the commands added later only have to use the
 * keys; add them to {@code USED_KEYS} as they are implemented.
 */
class LanguageFilesTest {

    /** Keys the plugin will look up once its commands exist; none is used yet. */
    private static final List<String> USED_KEYS = List.of();

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
    void theFilesAreReadableAndNotWrappedInARedundantBlock() {
        Map<String, String> english = load("en-US");

        assertFalse(english.isEmpty());
        assertTrue(english.containsKey("commands.transcribe"),
                "a wrapper would make this 'aiaudio.commands.transcribe' and every lookup would miss");
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
