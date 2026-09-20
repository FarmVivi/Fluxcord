package fr.farmvivi.fluxcord.core.language;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.language.events.LanguageLoadedEvent;
import fr.farmvivi.fluxcord.api.language.events.NamespaceRegisteredEvent;
import fr.farmvivi.fluxcord.api.language.events.StringRetrievalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of the LanguageManager interface with fallback to default resources.
 * This version adds event firing for language-related operations.
 */
public class SimpleLanguageManager implements LanguageManager {
    private static final Logger logger = LoggerFactory.getLogger(SimpleLanguageManager.class);

    // Event manager for firing language events
    private final EventManager eventManager;

    // The default locale
    private final Locale defaultLocale;

    // Map of locale code to Locale object
    private final Map<String, Locale> availableLocales = new ConcurrentHashMap<>();

    // Map of namespace to a map of locale to a map of key to value
    private final Map<String, Map<Locale, Map<String, String>>> translations = new ConcurrentHashMap<>();

    // Map of namespace to a map of locale to a map of key to value (default resources)
    private final Map<String, Map<Locale, Map<String, String>>> defaultTranslations = new ConcurrentHashMap<>();

    // Set of registered namespaces
    private final Map<String, Boolean> registeredNamespaces = new ConcurrentHashMap<>();

    /**
     * Creates a new language manager with the specified default locale.
     *
     * @param defaultLocale the default locale
     * @param eventManager  the event manager
     */
    public SimpleLanguageManager(Locale defaultLocale, EventManager eventManager) {
        this.defaultLocale = defaultLocale;
        this.eventManager = eventManager;
        availableLocales.put(defaultLocale.toLanguageTag(), defaultLocale);

        // Always register the "core" namespace
        if (logger.isDebugEnabled()) {
            logger.debug("Initializing SimpleLanguageManager with default locale {}", defaultLocale.toLanguageTag());
            logger.debug("Registering default namespace 'core'");
        }
        registerNamespace("core");

        // Load default resources
        if (logger.isDebugEnabled()) {
            logger.debug("Loading default language resources from classpath");
        }
        loadDefaultResources();
    }

    // For backward compatibility
    public SimpleLanguageManager(Locale defaultLocale) {
        this(defaultLocale, null);
    }

    /**
     * Loads default language resources from the JAR
     */
    private void loadDefaultResources() {
        // Load English and French by default
        if (logger.isDebugEnabled()) {
            logger.debug("Loading embedded default resource for core: en-US");
        }
        loadDefaultResource("core", Locale.forLanguageTag("en-US"), "/lang/en-US.yml");
        if (logger.isDebugEnabled()) {
            logger.debug("Loading embedded default resource for core: fr-FR");
        }
        loadDefaultResource("core", Locale.forLanguageTag("fr-FR"), "/lang/fr-FR.yml");
    }

    /**
     * Loads a default language resource from the JAR
     *
     * @param namespace    the namespace
     * @param locale       the locale
     * @param resourcePath the path to the resource
     */
    private void loadDefaultResource(String namespace, Locale locale, String resourcePath) {
        try {
            InputStream inputStream = getClass().getResourceAsStream(resourcePath);
            if (inputStream == null) {
                logger.warn("Default language resource not found: {}", resourcePath);
                return;
            }

            // Add the locale to available locales if it's not already there
            availableLocales.putIfAbsent(locale.toLanguageTag(), locale);

            // Load YAML from the resource
            Yaml yaml = new Yaml();
            try (InputStreamReader reader = new InputStreamReader(inputStream)) {
                logger.debug("Parsing YAML resource {} for namespace {} and locale {}", resourcePath, namespace, locale.toLanguageTag());
                if (logger.isDebugEnabled()) {
                    logger.debug("Parsing YAML resource {} for namespace {} and locale {}", resourcePath, namespace, locale.toLanguageTag());
                }
                Map<String, Object> langData = yaml.load(reader);
                if (langData != null) {
                    // Flatten the map
                    Map<String, String> flatMap = flattenMap(langData, "");

                    // Store the translations
                    Map<Locale, Map<String, String>> namespaceDefaultTranslations =
                            defaultTranslations.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>());
                    namespaceDefaultTranslations.put(locale, flatMap);

                    if (logger.isInfoEnabled()) {
                        logger.info("Loaded {} default strings for namespace {} and locale {} from resource",
                                flatMap.size(), namespace, locale.toLanguageTag());
                    }
                    if (logger.isDebugEnabled()) {
                        // Log a small sample of keys to help trace
                        int sampleCount = 0;
                        StringBuilder sb = new StringBuilder();
                        sb.append("Sample keys loaded [");
                        for (String k : flatMap.keySet()) {
                            if (sampleCount++ >= 10) break;
                            sb.append(k).append(", ");
                        }
                        if (sampleCount > 0) {
                            sb.setLength(Math.max(0, sb.length() - 2));
                        }
                        sb.append("]");
                        logger.debug("{}", sb);
                    }

                    // Fire the language loaded event
                    if (eventManager != null) {
                        eventManager.fireEvent(new LanguageLoadedEvent(
                                namespace, locale, flatMap.size(), new HashMap<>(flatMap)));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load default language resource: {}", resourcePath, e);
        }
    }

    /**
     * Flattens a nested map into a flat map with dot notation keys.
     *
     * @param map    the nested map
     * @param prefix the key prefix
     * @return a flattened map
     */
    private Map<String, String> flattenMap(Map<String, Object> map, String prefix) {
        Map<String, String> flatMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();

            if (entry.getValue() instanceof Map) {
                // Recursive flattening of nested maps
                flatMap.putAll(flattenMap((Map<String, Object>) entry.getValue(), key));
            } else {
                // Add leaf value
                flatMap.put(key, String.valueOf(entry.getValue()));
            }
        }

        return flatMap;
    }

    @Override
    public String getString(String key) {
        return getString(defaultLocale, key);
    }

    @Override
    public String getString(String key, Object... args) {
        return getString(defaultLocale, key, args);
    }

    @Override
    public String getString(Locale locale, String key) {
        if (logger.isDebugEnabled()) {
            logger.debug("getString(locale={}, key={}) invoked", locale.toLanguageTag(), key);
        }
        // Parse the key to get the namespace and the actual key
        String namespace = "core";
        String actualKey = key;

        if (key.contains(":")) {
            String[] parts = key.split(":", 2);
            namespace = parts[0];
            actualKey = parts[1];
        }

        // Check if the namespace is registered
        if (!registeredNamespaces.containsKey(namespace)) {
            logger.warn("Namespace not registered: {}", namespace);
            return key;
        }

        // Cascade de recherche de traduction:
        String translation;

        // 1. Essayer dans la locale spécifiée dans le dossier runtime
        translation = getTranslationFromRuntime(namespace, locale, actualKey);
        if (translation != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Translation hit (runtime) for [{}:{}] in locale {}", namespace, actualKey, locale.toLanguageTag());
            }
            // If we have an event manager, fire a string retrieval event
            if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                StringRetrievalEvent event = new StringRetrievalEvent(
                        namespace, locale, actualKey, null, translation);
                eventManager.fireEvent(event);

                // If the event overrode the value, use that instead
                if (event.isOverridden()) {
                    return event.getValue();
                }
            }
            return translation;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Translation miss (runtime) for [{}:{}] in locale {}", namespace, actualKey, locale.toLanguageTag());
        }

        // 2. Essayer dans la locale spécifiée dans les ressources par défaut
        translation = getTranslationFromResources(namespace, locale, actualKey);
        if (translation != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Translation hit (resources) for [{}:{}] in locale {}", namespace, actualKey, locale.toLanguageTag());
            }
            // If we have an event manager, fire a string retrieval event
            if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                StringRetrievalEvent event = new StringRetrievalEvent(
                        namespace, locale, actualKey, null, translation);
                eventManager.fireEvent(event);

                // If the event overrode the value, use that instead
                if (event.isOverridden()) {
                    return event.getValue();
                }
            }
            return translation;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Translation miss (resources) for [{}:{}] in locale {}", namespace, actualKey, locale.toLanguageTag());
        }

        // 2b. Chercher une variante de même langue (ex: fr -> fr-FR) avant de passer à l'anglais
        Locale sameLanguageVariant = findSameLanguageVariant(namespace, locale, actualKey);
        if (sameLanguageVariant != null) {
            // Runtime d'abord
            translation = getTranslationFromRuntime(namespace, sameLanguageVariant, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (runtime) for [{}:{}] using same-language variant {} -> {}",
                            namespace, actualKey, locale.toLanguageTag(), sameLanguageVariant.toLanguageTag());
                }
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, sameLanguageVariant, actualKey, null, translation);
                    eventManager.fireEvent(event);
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }

            // Puis ressources par défaut
            translation = getTranslationFromResources(namespace, sameLanguageVariant, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (resources) for [{}:{}] using same-language variant {} -> {}",
                            namespace, actualKey, locale.toLanguageTag(), sameLanguageVariant.toLanguageTag());
                }
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, sameLanguageVariant, actualKey, null, translation);
                    eventManager.fireEvent(event);
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }
        }

        // 3. Si la locale n'est pas la locale par défaut configurée, essayer celle-ci (runtime) — avant l'anglais,
        //    pour qu'un bot configuré fr-FR réponde en français à une locale inconnue
        if (!locale.equals(defaultLocale)) {
            translation = getTranslationFromRuntime(namespace, defaultLocale, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (runtime) for [{}:{}] in default locale {}", namespace, actualKey, defaultLocale.toLanguageTag());
                }
                // If we have an event manager, fire a string retrieval event
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, defaultLocale, actualKey, null, translation);
                    eventManager.fireEvent(event);

                    // If the event overrode the value, use that instead
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Translation miss (runtime) for [{}:{}] in default locale {}", namespace, actualKey, defaultLocale.toLanguageTag());
            }

            // 4. Essayer dans la locale par défaut des ressources
            translation = getTranslationFromResources(namespace, defaultLocale, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (resources) for [{}:{}] in default locale {}", namespace, actualKey, defaultLocale.toLanguageTag());
                }
                // If we have an event manager, fire a string retrieval event
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, defaultLocale, actualKey, null, translation);
                    eventManager.fireEvent(event);

                    // If the event overrode the value, use that instead
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Translation miss (resources) for [{}:{}] in default locale {}", namespace, actualKey, defaultLocale.toLanguageTag());
            }
        }

        // 5. Si la locale n'est pas l'anglais, essayer dans la locale anglaise du dossier runtime (filet final)
        if (!locale.getLanguage().equals("en")) {
            Locale englishLocale = Locale.forLanguageTag("en-US");
            translation = getTranslationFromRuntime(namespace, englishLocale, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (runtime) for [{}:{}] in fallback en-US", namespace, actualKey);
                }
                // If we have an event manager, fire a string retrieval event
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, englishLocale, actualKey, null, translation);
                    eventManager.fireEvent(event);

                    // If the event overrode the value, use that instead
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Translation miss (runtime) for [{}:{}] in fallback en-US", namespace, actualKey);
            }

            // 6. Essayer dans la locale anglaise des ressources par défaut
            translation = getTranslationFromResources(namespace, englishLocale, actualKey);
            if (translation != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Translation hit (resources) for [{}:{}] in fallback en-US", namespace, actualKey);
                }
                // If we have an event manager, fire a string retrieval event
                if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
                    StringRetrievalEvent event = new StringRetrievalEvent(
                            namespace, englishLocale, actualKey, null, translation);
                    eventManager.fireEvent(event);

                    // If the event overrode the value, use that instead
                    if (event.isOverridden()) {
                        return event.getValue();
                    }
                }
                return translation;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Translation miss (resources) for [{}:{}] in fallback en-US", namespace, actualKey);
            }
        }

        // 7. Si tout échoue, retourner la clé elle-même et logger un avertissement
        if (logger.isDebugEnabled()) {
            logger.debug("Translation not found for key: {} in locale: {} (namespace: {})",
                    actualKey, locale.toLanguageTag(), namespace);
        }

        // If we have an event manager, fire a string retrieval event - maybe someone can provide the string
        if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
            StringRetrievalEvent event = new StringRetrievalEvent(
                    namespace, locale, actualKey, null, key);
            eventManager.fireEvent(event);

            // If the event overrode the value, use that instead
            if (event.isOverridden()) {
                return event.getValue();
            }
        }

        return key;
    }

    /**
     * Trouve une variante de locale qui partage la même langue et contient la clé recherchée,
     * en inspectant d'abord les traductions runtime puis les ressources par défaut pour le namespace donné.
     */
    private Locale findSameLanguageVariant(String namespace, Locale requested, String key) {
        // Inspecter les traductions chargées au runtime pour ce namespace
        Map<Locale, Map<String, String>> nsRuntime = translations.get(namespace);
        if (nsRuntime != null) {
            for (Map.Entry<Locale, Map<String, String>> e : nsRuntime.entrySet()) {
                Locale candidate = e.getKey();
                if (candidate.equals(requested)) continue;
                if (!candidate.getLanguage().equalsIgnoreCase(requested.getLanguage())) continue;
                Map<String, String> map = e.getValue();
                if (map != null && map.containsKey(key)) {
                    return candidate;
                }
            }
        }

        // Inspecter les ressources par défaut
        Map<Locale, Map<String, String>> nsDefaults = defaultTranslations.get(namespace);
        if (nsDefaults != null) {
            for (Map.Entry<Locale, Map<String, String>> e : nsDefaults.entrySet()) {
                Locale candidate = e.getKey();
                if (candidate.equals(requested)) continue;
                if (!candidate.getLanguage().equalsIgnoreCase(requested.getLanguage())) continue;
                Map<String, String> map = e.getValue();
                if (map != null && map.containsKey(key)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    /**
     * Recherche une traduction dans les fichiers du dossier runtime.
     *
     * @param namespace le namespace
     * @param locale    la locale
     * @param key       la clé
     * @return la traduction, ou null si non trouvée
     */
    private String getTranslationFromRuntime(String namespace, Locale locale, String key) {
        Map<Locale, Map<String, String>> namespaceTranslations = translations.get(namespace);
        if (namespaceTranslations != null) {
            Map<String, String> localeTranslations = namespaceTranslations.get(locale);
            if (localeTranslations != null && localeTranslations.containsKey(key)) {
                return localeTranslations.get(key);
            }
        }
        return null;
    }

    /**
     * Recherche une traduction dans les ressources par défaut.
     *
     * @param namespace le namespace
     * @param locale    la locale
     * @param key       la clé
     * @return la traduction, ou null si non trouvée
     */
    private String getTranslationFromResources(String namespace, Locale locale, String key) {
        Map<Locale, Map<String, String>> namespaceDefaultTranslations = defaultTranslations.get(namespace);
        if (namespaceDefaultTranslations != null) {
            Map<String, String> localeDefaultTranslations = namespaceDefaultTranslations.get(locale);
            if (localeDefaultTranslations != null && localeDefaultTranslations.containsKey(key)) {
                return localeDefaultTranslations.get(key);
            }
        }
        return null;
    }

    @Override
    public String getString(Locale locale, String key, Object... args) {
        String value = getString(locale, key);
        if (value.equals(key)) {
            return key;
        }

        // If we have an event manager, fire a string retrieval event with the args
        if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
            String namespace = "core";
            String actualKey = key;

            if (key.contains(":")) {
                String[] parts = key.split(":", 2);
                namespace = parts[0];
                actualKey = parts[1];
            }

            StringRetrievalEvent event = new StringRetrievalEvent(
                    namespace, locale, actualKey, args, value);
            eventManager.fireEvent(event);

            // If the event overrode the value, use that instead
            if (event.isOverridden()) {
                value = event.getValue();
            }
        }

        // Replace placeholders using MessageFormat
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Formatting translation for key {} with {} arg(s)", key, args == null ? 0 : args.length);
            }
            return MessageFormat.format(value, args);
        } catch (Exception e) {
            logger.warn("Failed to format string: {} with args: {}", value, args, e);
            return value;
        }
    }

    @Override
    public boolean registerNamespace(String namespace) {
        if (registeredNamespaces.containsKey(namespace)) {
            logger.debug("Namespace '{}' already registered", namespace);
            return false;
        }

        registeredNamespaces.put(namespace, true);
        translations.put(namespace, new ConcurrentHashMap<>());
        defaultTranslations.put(namespace, new ConcurrentHashMap<>());
        if (logger.isDebugEnabled()) {
            logger.debug("Registered namespace '{}'", namespace);
        }

        // Fire the namespace registered event
        if (eventManager != null) {
            eventManager.fireEvent(new NamespaceRegisteredEvent(namespace));
        }

        return true;
    }

    @Override
    public int loadLanguage(String namespace, Locale locale, Map<String, String> strings) {
        // Check if the namespace is registered
        if (!registeredNamespaces.containsKey(namespace)) {
            logger.warn("Cannot load language for unregistered namespace: {}", namespace);
            return 0;
        }

        // Add the locale to available locales if it's not already there
        availableLocales.putIfAbsent(locale.toLanguageTag(), locale);

        // Get or create the translations for the namespace
        Map<Locale, Map<String, String>> namespaceTranslations = translations.computeIfAbsent(
                namespace, k -> new ConcurrentHashMap<>());

        // Get or create the translations for the locale
        Map<String, String> localeTranslations = namespaceTranslations.computeIfAbsent(
                locale, k -> new ConcurrentHashMap<>());

        // Add the strings
        localeTranslations.putAll(strings);

        if (logger.isInfoEnabled()) {
            logger.info("Loaded {} strings for namespace {} and locale {}",
                    strings.size(), namespace, locale.toLanguageTag());
        }

        // Fire the language loaded event
        if (eventManager != null) {
            eventManager.fireEvent(new LanguageLoadedEvent(
                    namespace, locale, strings.size(), new HashMap<>(strings)));
        }

        return strings.size();
    }

    @Override
    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    @Override
    public Map<String, Locale> getAvailableLocales() {
        return new HashMap<>(availableLocales);
    }
}