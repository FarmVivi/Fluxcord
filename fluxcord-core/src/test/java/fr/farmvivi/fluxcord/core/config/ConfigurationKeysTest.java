package fr.farmvivi.fluxcord.core.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped {@code config.yml} against the keys the code reads, in both directions.
 *
 * <p>A key read but not shipped is a setting nobody can discover: {@code data.storage.file.folder} —
 * where the FILE backend writes everything — was honoured by {@code CoreSettings} and mentioned nowhere,
 * while its binary counterpart was documented. A key shipped but never read is worse than useless: it
 * looks like a setting and changes nothing, which is how {@code commands.cooldown} spent its life being
 * injected into everyone's config by the migration while no code path ever looked at it.
 *
 * <p>The scan is a regex over the sources rather than a hand-written list, so it cannot drift.
 */
class ConfigurationKeysTest {

    /**
     * A key literal handed to a configuration getter.
     *
     * <p>The receiver has to be matched: the same method names exist on the language manager, and those
     * literals are translation keys.
     */
    private static final Pattern CONFIG_READ = Pattern.compile(
            "(\\w+)\\s*(?:\\(\\)\\s*)?\\.\\s*"
                    + "get(?:String|Int|Boolean|Long|Double|StringList)\\s*\\(\\s*\"([^\"]+)\"");

    /** Read by the framework itself, not by any code path of this module. */
    private static final Set<String> FRAMEWORK_KEYS = Set.of("config_version");

    @Test
    void everyKeyTheCodeReadsIsInTheShippedConfig() {
        Set<String> declared = declaredKeys();
        List<String> undocumented = new ArrayList<>();
        for (String key : keysReadInSources()) {
            if (!declared.contains(key)) {
                undocumented.add(key);
            }
        }
        assertEquals(List.of(), undocumented,
                "these settings are honoured but absent from config.yml, so nobody can find them");
    }

    @Test
    void everyKeyTheConfigShipsIsActuallyRead() {
        Set<String> read = keysReadInSources();
        List<String> decorative = new ArrayList<>();
        for (String key : declaredLeafKeys()) {
            if (!read.contains(key) && !FRAMEWORK_KEYS.contains(key)) {
                decorative.add(key);
            }
        }
        assertEquals(List.of(), decorative,
                "these settings are shipped but never read: they look like settings and change nothing");
    }

    /**
     * Whether a receiver is a configuration rather than, say, the language manager, whose methods have the
     * same names.
     *
     * <p>Decided here instead of inside the pattern: expressing "an identifier ending in config" needs a
     * repetition that overlaps what follows it, and that backtracks badly on long non-matching lines.
     */
    private boolean isConfigurationReceiver(String receiver) {
        return receiver.equals("getConfiguration")
                || receiver.toLowerCase(java.util.Locale.ROOT).contains("config");
    }

    /** Every key literal the module hands to a configuration getter. */
    private Set<String> keysReadInSources() {
        Path sources = Path.of("src/main/java");
        assertTrue(Files.isDirectory(sources), "run this test from the module directory");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                Matcher matcher = CONFIG_READ.matcher(read(path));
                while (matcher.find()) {
                    if (isConfigurationReceiver(matcher.group(1))) {
                        keys.add(matcher.group(2));
                    }
                }
            });
        } catch (IOException e) {
            throw new AssertionError("cannot scan the sources", e);
        }
        return keys;
    }

    /** Every path in the shipped file, sections included. */
    private Set<String> declaredKeys() {
        Set<String> keys = new TreeSet<>();
        collect(shippedConfig(), "", keys, false);
        return keys;
    }

    /** Only the leaves: a section is a grouping, not a setting. */
    private Set<String> declaredLeafKeys() {
        Set<String> keys = new TreeSet<>();
        collect(shippedConfig(), "", keys, true);
        return keys;
    }

    private void collect(Map<?, ?> node, String prefix, Set<String> into, boolean leavesOnly) {
        node.forEach((key, value) -> {
            String path = prefix + key;
            if (value instanceof Map<?, ?> child) {
                if (!leavesOnly) {
                    into.add(path);
                }
                collect(child, path + ".", into, leavesOnly);
            } else {
                into.add(path);
            }
        });
    }

    private Map<?, ?> shippedConfig() {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "the module ships no config.yml");
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new AssertionError("cannot read config.yml", e);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }
}
