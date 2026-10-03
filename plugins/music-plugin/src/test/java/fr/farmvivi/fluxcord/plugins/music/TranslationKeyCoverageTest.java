package fr.farmvivi.fluxcord.plugins.music;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the lang files and the code that reads them in step, in both directions.
 *
 * <p>The sibling {@code LanguageFilesTest} checks a hand-written list of keys, which is the thing that goes
 * stale: nobody remembers to extend it, so a key added to the code and forgotten in the file is found by a
 * user reading {@code commands.titles.success} in Discord instead of "Done" — which is exactly how that key
 * shipped. This test writes the list itself, by reading the sources.
 *
 * <p>It works on string literals, so it is deliberately conservative about what it calls a key:
 * <ul>
 *   <li>the literal must <em>look</em> like one (lowercase, dotted, no spaces);
 *   <li>its parent path must be a section of the English file that already holds at least one translation.
 *       {@code commands.prefix} is a config key and {@code commands} holds only sub-sections, so it is not
 *       mistaken for a missing translation, while {@code commands.titles.success} is caught because
 *       {@code commands.titles} is full of them;
 *   <li>and it must not be read from the configuration, which in a plugin shares the {@code music.} and
 *       {@code commands.} prefixes with the lang file.
 * </ul>
 *
 * <p>The other direction — a translation nobody looks up — tolerates the keys that are built by
 * concatenation ({@code "commands." + name + ".description"}): a literal ending in a dot vouches for
 * everything under it, and one starting with a dot for everything ending in it. That is weaker than the
 * forward check, which is the right way round: a missing translation is visible to users, an extra one is
 * only clutter.
 */
class TranslationKeyCoverageTest {

    /** Where the module's own code lives, relative to the module directory Surefire runs in. */
    private static final Path SOURCES = Path.of("src", "main", "java");

    /** Lowercase dotted path, the shape every key in these files has. */
    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+");

    /** A Java string literal, escapes included so a {@code \"} does not end it early. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    /** Reading the plugin's own configuration, which has keys of exactly the same shape. */
    private static final Pattern CONFIGURATION = Pattern.compile(
            "(?:getConfiguration\\(\\)|config|configuration|settings)\\s*\\.\\s*get\\w*\\(\\s*$");

    @Test
    void everyKeyTheCodeAsksForIsTranslated() {
        Translations english = Translations.load(getClass(), "en-US");
        Map<String, Set<String>> literals = literals();

        List<String> missing = new ArrayList<>();
        literals.forEach((literal, where) -> {
            if (!KEY_SHAPE.matcher(literal).matches() || english.holds(literal) || english.isSection(literal)) {
                return;
            }
            String parent = literal.substring(0, literal.lastIndexOf('.'));
            if (english.translatesSomethingDirectlyUnder(parent)) {
                missing.add(literal + " (asked in " + String.join(", ", new TreeSet<>(where)) + ")");
            }
        });

        assertTrue(missing.isEmpty(),
                "the code asks for translations that en-US.yml does not have, so users see the key itself:\n  "
                        + String.join("\n  ", missing));
    }

    @Test
    void everyTranslationIsReachedBySomeLookup() {
        Translations english = Translations.load(getClass(), "en-US");
        Set<String> literals = literals().keySet();

        List<String> orphans = english.keys().stream()
                .filter(key -> literals.stream().noneMatch(literal -> covers(literal, key)))
                .toList();

        assertTrue(orphans.isEmpty(),
                "these translations are no longer looked up anywhere; delete them or the code that lost "
                        + "them:\n  " + String.join("\n  ", orphans));
    }

    /** Whether a literal in the sources accounts for this key being looked up. */
    private static boolean covers(String literal, String key) {
        if (literal.equals(key)) {
            return true;
        }
        if (literal.endsWith(".") && literal.length() > 1 && key.startsWith(literal)) {
            return true;
        }
        return literal.startsWith(".") && literal.length() > 1 && key.endsWith(literal);
    }

    /** Every string literal of the module's sources, mapped to the files it appears in. */
    private static Map<String, Set<String>> literals() {
        Map<String, Set<String>> found = new TreeMap<>();
        assertTrue(Files.isDirectory(SOURCES), "expected to run from the module directory, with " + SOURCES);
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                Matcher matcher = LITERAL.matcher(source);
                while (matcher.find()) {
                    String before = source.substring(Math.max(0, matcher.start() - 60), matcher.start());
                    if (!CONFIGURATION.matcher(before).find()) {
                        found.computeIfAbsent(matcher.group(1), key -> new TreeSet<>())
                                .add(path.getFileName().toString());
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    /** One locale, flattened the way the core flattens it. */
    private record Translations(Map<String, String> leaves, Set<String> sections, Set<String> withLeaves) {

        static Translations load(Class<?> anchor, String locale) {
            try (InputStream in = anchor.getResourceAsStream("/lang/" + locale + ".yml")) {
                assertTrue(in != null, "missing language file for " + locale);
                Map<String, Object> raw = new Yaml().load(in);
                Translations translations =
                        new Translations(new LinkedHashMap<>(), new TreeSet<>(), new TreeSet<>());
                translations.flatten("", raw);
                return translations;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + locale, e);
            }
        }

        @SuppressWarnings("unchecked")
        private void flatten(String prefix, Map<String, Object> node) {
            boolean holdsATranslation = false;
            for (Map.Entry<String, Object> entry : node.entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> child) {
                    sections.add(path);
                    flatten(path, (Map<String, Object>) child);
                } else {
                    leaves.put(path, String.valueOf(entry.getValue()));
                    holdsATranslation = true;
                }
            }
            if (holdsATranslation && !prefix.isEmpty()) {
                withLeaves.add(prefix);
            }
        }

        Set<String> keys() {
            return leaves.keySet();
        }

        boolean holds(String key) {
            return leaves.containsKey(key);
        }

        boolean isSection(String path) {
            return sections.contains(path);
        }

        boolean translatesSomethingDirectlyUnder(String path) {
            return withLeaves.contains(path);
        }
    }
}
