package fr.farmvivi.fluxcord.core.language;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.language.events.LanguageLoadedEvent;
import fr.farmvivi.fluxcord.api.language.events.NamespaceRegisteredEvent;
import fr.farmvivi.fluxcord.api.language.events.StringRetrievalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translations by namespace and locale, with one lookup cascade.
 * <p>
 * Keys are {@code namespace:key} ({@code core:} when no namespace is given); namespaces are plugin ids, compared
 * case-insensitively. Strings arrive through {@link #loadLanguage} in load order — the core's bundled
 * {@code en-US}/{@code fr-FR} first, then the {@code lang/} runtime folder; a plugin's jar strings, then its
 * {@code plugins/<id>/lang/} folder — and later loads override earlier ones key by key.
 * <p>
 * {@link #getString(Locale, String)} tries, in order: the requested locale, another locale of the same language
 * that has the key ({@code fr} → {@code fr-FR}), the configured default locale, then {@code en-US}. The result
 * (or the miss, which returns the key itself) is offered once to {@link StringRetrievalEvent} listeners.
 */
public class SimpleLanguageManager implements LanguageManager {
    private static final Logger logger = LoggerFactory.getLogger(SimpleLanguageManager.class);
    public static final String CORE_NAMESPACE = "core";
    private static final Locale FALLBACK = Locale.US;

    private final EventManager eventManager;
    private final Locale defaultLocale;
    private final Map<String, Locale> availableLocales = new ConcurrentHashMap<>();
    /** namespace (lower-case) → locale → flat key → string */
    private final Map<String, Map<Locale, Map<String, String>>> translations = new ConcurrentHashMap<>();

    /**
     * @param defaultLocale the bot's locale: tried before {@code en-US} for every lookup
     * @param eventManager  the event bus, or null for a silent manager (tests)
     */
    public SimpleLanguageManager(Locale defaultLocale, EventManager eventManager) {
        this.defaultLocale = Objects.requireNonNull(defaultLocale, "defaultLocale");
        this.eventManager = eventManager;
        availableLocales.put(defaultLocale.toLanguageTag(), defaultLocale);
        registerNamespace(CORE_NAMESPACE);
        LanguageFiles.loadResource(this, CORE_NAMESPACE, Locale.US, "/lang/en-US.yml");
        LanguageFiles.loadResource(this, CORE_NAMESPACE, Locale.FRANCE, "/lang/fr-FR.yml");
    }

    public SimpleLanguageManager(Locale defaultLocale) {
        this(defaultLocale, null);
    }

    // ---- lookup -----------------------------------------------------------------------------------------------

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
        return lookup(locale, key, null);
    }

    @Override
    public String getString(Locale locale, String key, Object... args) {
        String value = lookup(locale, key, args);
        if (value.equals(key)) {
            return key;
        }
        try {
            return MessageFormat.format(escapeApostrophes(value), args);
        } catch (Exception e) {
            logger.warn("Failed to format '{}' with {} argument(s): {}", value, args == null ? 0 : args.length, e.getMessage());
            return value;
        }
    }

    /**
     * A lone apostrophe is MessageFormat's quoting character and would swallow the placeholders that follow
     * ({@code "l'utilisateur {1}"} → {@code "lutilisateur {1}"}); translators write natural text, so lone
     * apostrophes are doubled here. An already doubled one is left alone.
     */
    static String escapeApostrophes(String pattern) {
        return pattern.indexOf('\'') < 0 ? pattern : pattern.replaceAll("(?<!')'(?!')", "''");
    }

    private String lookup(Locale locale, String key, Object[] args) {
        int colon = key.indexOf(':');
        String namespace = colon < 0 ? CORE_NAMESPACE : normalize(key.substring(0, colon));
        String actualKey = colon < 0 ? key : key.substring(colon + 1);

        Map<Locale, Map<String, String>> byLocale = translations.get(namespace);
        if (byLocale == null) {
            logger.warn("Namespace not registered: {}", namespace);
            return key;
        }

        Locale answered = null;
        String value = null;
        for (Locale candidate : candidates(byLocale, locale, actualKey)) {
            Map<String, String> strings = byLocale.get(candidate);
            if (strings != null && strings.containsKey(actualKey)) {
                answered = candidate;
                value = strings.get(actualKey);
                break;
            }
        }
        if (value == null && logger.isDebugEnabled()) {
            logger.debug("Translation not found for [{}:{}] in locale {}", namespace, actualKey, locale.toLanguageTag());
        }

        // Listeners see every lookup once, hits (with the locale that answered) and misses alike, and may override
        if (eventManager != null && eventManager.hasListeners(StringRetrievalEvent.class)) {
            StringRetrievalEvent event = new StringRetrievalEvent(namespace, answered != null ? answered : locale,
                    actualKey, args, value != null ? value : key);
            eventManager.fireEvent(event);
            if (event.isOverridden()) {
                return event.getValue();
            }
        }
        return value != null ? value : key;
    }

    /** Requested locale → same-language variant holding the key → configured default → en-US, without repeats. */
    private List<Locale> candidates(Map<Locale, Map<String, String>> byLocale, Locale requested, String key) {
        List<Locale> candidates = new ArrayList<>(4);
        candidates.add(requested);
        if (!defaultLocale.equals(requested) && defaultLocale.getLanguage().equalsIgnoreCase(requested.getLanguage())
                && byLocale.getOrDefault(defaultLocale, Map.of()).containsKey(key)) {
            candidates.add(defaultLocale); // the configured variant wins over any other variant of the language
        }
        for (Map.Entry<Locale, Map<String, String>> entry : byLocale.entrySet()) {
            Locale candidate = entry.getKey();
            if (!candidates.contains(candidate) && candidate.getLanguage().equalsIgnoreCase(requested.getLanguage())
                    && entry.getValue().containsKey(key)) {
                candidates.add(candidate);
                break;
            }
        }
        if (!candidates.contains(defaultLocale)) {
            candidates.add(defaultLocale);
        }
        if (!candidates.contains(FALLBACK)) {
            candidates.add(FALLBACK);
        }
        return candidates;
    }

    // ---- registration -----------------------------------------------------------------------------------------

    @Override
    public boolean registerNamespace(String namespace) {
        String normalized = normalize(namespace);
        if (translations.putIfAbsent(normalized, new ConcurrentHashMap<>()) != null) {
            return false;
        }
        logger.debug("Registered language namespace '{}'", normalized);
        if (eventManager != null) {
            eventManager.fireEvent(new NamespaceRegisteredEvent(normalized));
        }
        return true;
    }

    @Override
    public int loadLanguage(String namespace, Locale locale, Map<String, String> strings) {
        String normalized = normalize(namespace);
        Map<Locale, Map<String, String>> byLocale = translations.get(normalized);
        if (byLocale == null) {
            logger.warn("Cannot load language for unregistered namespace: {}", namespace);
            return 0;
        }
        availableLocales.putIfAbsent(locale.toLanguageTag(), locale);
        byLocale.computeIfAbsent(locale, l -> new ConcurrentHashMap<>()).putAll(strings);
        logger.info("Loaded {} strings for namespace {} and locale {}", strings.size(), normalized, locale.toLanguageTag());
        if (eventManager != null) {
            eventManager.fireEvent(new LanguageLoadedEvent(normalized, locale, strings.size(), new HashMap<>(strings)));
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

    /** Namespaces are plugin ids; ids differ only by case in practice, so they are compared lower-cased. */
    private static String normalize(String namespace) {
        return namespace.toLowerCase(Locale.ROOT);
    }
}
