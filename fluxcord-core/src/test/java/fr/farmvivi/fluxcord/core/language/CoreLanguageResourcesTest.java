package fr.farmvivi.fluxcord.core.language;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's own {@code lang/*.yml}, checked as files rather than through the loader.
 *
 * <p>These are the strings every bot shows without a plugin being involved, and two of them were broken:
 * {@code commands.messages.execution_error} and {@code errors.*} carried a lone apostrophe next to a
 * placeholder, so MessageFormat treated the rest of the message as a quoted literal and printed
 * {@code {0}} to the user instead of the error. Nothing tested the resource files, only the loader.
 *
 * <p>The same three checks live in each plugin module's own {@code LanguageFilesTest}; they cannot be
 * shared, since no test artifact is published between modules.
 */
class CoreLanguageResourcesTest {

    private static final List<String> LOCALES = List.of("en-US", "fr-FR");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private Map<String, String> flat(String locale) {
        try (InputStream in = resource(locale)) {
            return LanguageFiles.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }

    private Map<String, Object> raw(String locale) {
        try (InputStream in = resource(locale)) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }

    private InputStream resource(String locale) {
        InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml");
        assertNotNull(in, "missing language file for " + locale);
        return in;
    }

    @Test
    void everyKeyIsAStringAndNotSomethingYamlResolvedForUs() {
        // off/on/yes/no/true/false and bare numbers are not strings in YAML 1.1, which snakeyaml
        // implements: such a key is flattened through String.valueOf and stops matching what the code
        // asks for.
        for (String locale : LOCALES) {
            assertStringKeys(locale, "", raw(locale));
        }
    }

    private void assertStringKeys(String locale, String prefix, Map<?, ?> node) {
        node.forEach((key, value) -> {
            assertInstanceOf(String.class, key,
                    locale + ": the key '" + prefix + key + "' is a " + key.getClass().getSimpleName()
                            + ", not a string - quote it in the YAML");
            if (value instanceof Map<?, ?> child) {
                assertStringKeys(locale, prefix + key + ".", child);
            }
        });
    }

    @Test
    void aFormattedStringNeverCarriesALoneApostrophe() {
        // MessageFormat swallows a single quote and everything after it up to the next one, so a value
        // taking arguments must double its apostrophes. Only formatted values go through it.
        for (String locale : LOCALES) {
            flat(locale).forEach((key, value) -> {
                if (PLACEHOLDER.matcher(value).find()) {
                    assertFalse(value.replace("''", "").contains("'"),
                            locale + ": '" + key + "' takes arguments, so its apostrophes must be doubled");
                }
            });
        }
    }

    @Test
    void theTwoLocalesDeclareTheSameKeys() {
        assertEquals(new TreeSet<>(flat("en-US").keySet()), new TreeSet<>(flat("fr-FR").keySet()),
                "a key translated in one locale only falls back silently to English");
    }

    @Test
    void placeholdersMatchBetweenLocales() {
        Map<String, String> english = flat("en-US");
        Map<String, String> french = flat("fr-FR");

        english.forEach((key, value) -> assertEquals(placeholders(value), placeholders(french.get(key)),
                "'" + key + "' does not take the same arguments in both locales"));
    }

    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
