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

    /** @return the bot's default locale (what text and console commands use) */
    public Locale getDefaultLocale() {
        return languageManager.getDefaultLocale();
    }

    /** @return the shared language manager, for the rare cases that need to leave this plugin's namespace */
    public LanguageManager getLanguageManager() {
        return languageManager;
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
        return logResult(key, null, languageManager.getString(fullKey), fullKey);
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
        return logResult(key, null, languageManager.getString(fullKey, args), fullKey);
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
        return logResult(key, locale, languageManager.getString(locale, fullKey), fullKey);
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
        return logResult(key, locale, languageManager.getString(locale, fullKey, args), fullKey);
    }

    /**
     * Logs what a lookup produced, and warns when it produced nothing.
     *
     * <p>The manager signals a miss by returning the key it was given, which is otherwise invisible: the
     * user simply sees {@code myplugin:commands.foo.description} in Discord. Reporting it here is the only
     * place that knows both the key asked for and the answer.
     *
     * <p>These logs used to print the namespaced key as if it were the result, which reads like a failed
     * lookup even when the lookup worked — misleading enough to have sent a reader hunting a bug that was
     * not there.
     *
     * @param key      the key as the plugin asked for it
     * @param locale   the locale asked for, or null for the default one
     * @param resolved what the language manager answered
     * @param fullKey  the namespaced key, which is also what a miss returns
     * @return {@code resolved}, unchanged
     */
    private String logResult(String key, Locale locale, String resolved, String fullKey) {
        if (fullKey.equals(resolved)) {
            logger.warn("[{}] no translation for '{}'{}; the key itself will be shown to the user",
                    pluginName, key, locale == null ? "" : " in " + locale.toLanguageTag());
        } else if (logger.isDebugEnabled()) {
            logger.debug("[{}] '{}'{} = \"{}\"", pluginName, key,
                    locale == null ? "" : " (" + locale.toLanguageTag() + ")", resolved);
        }
        return resolved;
    }
}