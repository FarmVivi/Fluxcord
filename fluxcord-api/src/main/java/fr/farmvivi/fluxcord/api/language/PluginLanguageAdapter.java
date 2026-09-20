package fr.farmvivi.fluxcord.api.language;

import fr.farmvivi.fluxcord.api.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * A simplified language manager for plugins.
 * This class wraps the core language manager and handles namespace prefixing automatically.
 */
public class PluginLanguageAdapter {
    private static final Logger logger = LoggerFactory.getLogger(PluginLanguageAdapter.class);
    private final LanguageManager languageManager;
    private final String namespace;
    private final String pluginName;

    /**
     * Creates a new plugin language manager.
     *
     * @param plugin          the plugin
     * @param languageManager the core language manager
     */
    public PluginLanguageAdapter(Plugin plugin, LanguageManager languageManager) {
        this(plugin.getId(), plugin.getName(), languageManager);
    }

    /**
     * Same, from the plugin id and name alone (the core builds the adapter before the plugin instance is
     * initialised). Registers the namespace {@code pluginId}.
     */
    public PluginLanguageAdapter(String pluginId, String pluginName, LanguageManager languageManager) {
        this.languageManager = languageManager;
        this.namespace = pluginId;
        this.pluginName = pluginName;

        // Register the namespace automatically
        boolean registered = languageManager.registerNamespace(namespace);
        if (registered) {
            logger.debug("Registered plugin namespace '{}' for plugin {}", namespace, pluginName);
        } else {
            logger.debug("Plugin namespace '{}' already registered for plugin {}", namespace, pluginName);
        }
    }

    /**
     * Gets a translated string for the specified key in the default language.
     * The namespace is automatically added.
     *
     * @param key the translation key (without namespace prefix)
     * @return the translated string, or the key itself if not found
     */
    public String getString(String key) {
        String fullKey = namespace + ":" + key;
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getString key='{}' -> '{}'", pluginName, key, fullKey);
        }
        return languageManager.getString(fullKey);
    }

    /**
     * Gets a translated string with placeholder replacements.
     * The namespace is automatically added.
     *
     * @param key  the translation key (without namespace prefix)
     * @param args the arguments to replace placeholders
     * @return the translated string with replacements
     */
    public String getString(String key, Object... args) {
        String fullKey = namespace + ":" + key;
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getString key='{}' with {} arg(s) -> '{}'", pluginName, key, args == null ? 0 : args.length, fullKey);
        }
        return languageManager.getString(fullKey, args);
    }

    /**
     * Gets a translated string for the specified locale.
     * The namespace is automatically added.
     *
     * @param locale the locale
     * @param key    the translation key (without namespace prefix)
     * @return the translated string, or the key itself if not found
     */
    public String getString(Locale locale, String key) {
        String fullKey = namespace + ":" + key;
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getString locale={}, key='{}' -> '{}'", pluginName, locale.toLanguageTag(), key, fullKey);
        }
        return languageManager.getString(locale, fullKey);
    }

    /**
     * Gets a translated string for the specified locale with placeholder replacements.
     * The namespace is automatically added.
     *
     * @param locale the locale
     * @param key    the translation key (without namespace prefix)
     * @param args   the arguments to replace placeholders
     * @return the translated string with replacements
     */
    public String getString(Locale locale, String key, Object... args) {
        String fullKey = namespace + ":" + key;
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getString locale={}, key='{}' with {} arg(s) -> '{}'", pluginName, locale.toLanguageTag(), key, args == null ? 0 : args.length, fullKey);
        }
        return languageManager.getString(locale, fullKey, args);
    }
}