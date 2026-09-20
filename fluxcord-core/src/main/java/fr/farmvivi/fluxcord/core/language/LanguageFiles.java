package fr.farmvivi.fluxcord.core.language;

import fr.farmvivi.fluxcord.api.language.LanguageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The one way language YAML files become translations: a file {@code <locale>.yml} (e.g. {@code fr-FR.yml},
 * {@code fr_FR.yml}) whose nested sections are flattened to dotted keys ({@code commands.messages.cooldown}) and
 * whose leaves become strings. Used for the core's bundled resources, the {@code lang/} runtime folders (core and
 * plugins) and the {@code lang/} entries of plugin jars.
 */
public final class LanguageFiles {
    private static final Logger logger = LoggerFactory.getLogger(LanguageFiles.class);

    private LanguageFiles() {
    }

    /** Parses one YAML document into flat dotted keys; an empty document gives an empty map. */
    public static Map<String, String> parse(Reader reader) {
        Object data = new Yaml().load(reader);
        Map<String, String> flat = new HashMap<>();
        if (data instanceof Map<?, ?> map) {
            flatten(map, "", flat);
        }
        return flat;
    }

    /** {@code fr-FR.yml} / {@code fr_FR.yml} → {@code fr-FR}. */
    public static Locale localeOf(String fileName) {
        String code = fileName.endsWith(".yml") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return Locale.forLanguageTag(code.replace('_', '-'));
    }

    /**
     * Loads every {@code *.yml} of a folder into {@code namespace}; a missing folder loads nothing.
     *
     * @return the number of strings loaded
     */
    public static int loadFolder(LanguageManager languageManager, String namespace, File folder) {
        File[] files = folder.isDirectory() ? folder.listFiles((dir, name) -> name.endsWith(".yml")) : null;
        if (files == null) {
            return 0;
        }
        int loaded = 0;
        for (File file : files) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                loaded += languageManager.loadLanguage(namespace, localeOf(file.getName()), parse(reader));
            } catch (Exception e) {
                logger.error("Failed to load language file {} (namespace '{}')", file.getAbsolutePath(), namespace, e);
            }
        }
        return loaded;
    }

    /**
     * Loads every {@code lang/*.yml} entry of a jar into {@code namespace}.
     *
     * @return the number of strings loaded
     */
    public static int loadJar(LanguageManager languageManager, String namespace, JarFile jar) {
        int loaded = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith("lang/") || !name.endsWith(".yml") || name.indexOf('/', 5) >= 0) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                loaded += languageManager.loadLanguage(namespace, localeOf(name.substring(5)), parse(reader));
            } catch (Exception e) {
                logger.error("Failed to load language entry {} of {} (namespace '{}')", name, jar.getName(), namespace, e);
            }
        }
        return loaded;
    }

    /**
     * Loads one classpath resource into {@code namespace}; a missing resource loads nothing.
     *
     * @return the number of strings loaded
     */
    public static int loadResource(LanguageManager languageManager, String namespace, Locale locale, String resourcePath) {
        try (InputStream in = LanguageFiles.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                logger.warn("Language resource not found: {}", resourcePath);
                return 0;
            }
            return languageManager.loadLanguage(namespace, locale, parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            logger.error("Failed to load language resource {}", resourcePath, e);
            return 0;
        }
    }

    private static void flatten(Map<?, ?> map, String prefix, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> section) {
                flatten(section, key, out);
            } else if (entry.getValue() != null) {
                out.put(key, String.valueOf(entry.getValue()));
            }
        }
    }
}
