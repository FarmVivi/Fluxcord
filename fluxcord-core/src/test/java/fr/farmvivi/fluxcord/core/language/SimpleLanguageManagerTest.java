package fr.farmvivi.fluxcord.core.language;

import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.language.events.LanguageLoadedEvent;
import fr.farmvivi.fluxcord.api.language.events.NamespaceRegisteredEvent;
import fr.farmvivi.fluxcord.api.language.events.StringRetrievalEvent;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the translation lookup cascade, the missing-key contract, MessageFormat
 * arguments and the language events.
 */
class SimpleLanguageManagerTest {

    private static final Locale EN_US = Locale.forLanguageTag("en-US");
    private static final Locale FR_FR = Locale.forLanguageTag("fr-FR");
    private static final Locale FR = Locale.forLanguageTag("fr");
    private static final Locale DE_DE = Locale.forLanguageTag("de-DE");

    private final SimpleLanguageManager manager = new SimpleLanguageManager(EN_US);

    // --- bundled core resources -----------------------------------------------------------------

    @Test
    void coreResourcesAreLoadedForEnglishAndFrench() {
        assertEquals(Map.of("en-US", EN_US, "fr-FR", FR_FR), manager.getAvailableLocales());
        assertEquals(EN_US, manager.getDefaultLocale());
        assertEquals("This command is disabled", manager.getString("commands.messages.disabled"));
        assertEquals("Cette commande est désactivée", manager.getString(FR_FR, "commands.messages.disabled"));
        assertEquals("This command is disabled", manager.getString("core:commands.messages.disabled"),
                "'core:' prefix is the implicit namespace");
    }

    @Test
    void nestedYamlIsFlattenedToDottedKeys() {
        assertTrue(manager.getString("permissions.error.denied").startsWith("Permission denied"));
        assertEquals("permissions", manager.getString("permissions"), "a section is not a string");
    }

    // --- missing keys and namespaces ------------------------------------------------------------

    @Test
    void missingKeyReturnsTheKeyItself() {
        assertEquals("nope.missing", manager.getString("nope.missing"));
        assertEquals("core:nope.missing", manager.getString("core:nope.missing"), "returned as given, prefix included");
        assertEquals("nope.missing", manager.getString(FR_FR, "nope.missing", "arg"), "no formatting on a miss");
    }

    @Test
    void unregisteredNamespaceReturnsTheKey() {
        assertEquals("ghost:some.key", manager.getString("ghost:some.key"));
        assertEquals(0, manager.loadLanguage("ghost", EN_US, Map.of("some.key", "x")));
        assertEquals("ghost:some.key", manager.getString("ghost:some.key"));
    }

    @Test
    void namespacesRegisterOnce() {
        assertTrue(manager.registerNamespace("music"));
        assertFalse(manager.registerNamespace("music"));
        assertFalse(manager.registerNamespace("core"), "core is registered by the constructor");
    }

    // --- cascade --------------------------------------------------------------------------------

    @Test
    void runtimeStringsOverrideBundledResources() {
        manager.loadLanguage("core", EN_US, Map.of("commands.messages.disabled", "Nope"));

        assertEquals("Nope", manager.getString("commands.messages.disabled"));
        assertEquals("Cette commande est désactivée", manager.getString(FR_FR, "commands.messages.disabled"),
                "other locales untouched");
    }

    @Test
    void loadLanguageMergesIntoTheExistingLocale() {
        manager.registerNamespace("music");
        assertEquals(2, manager.loadLanguage("music", EN_US, Map.of("a", "1", "b", "2")));
        assertEquals(1, manager.loadLanguage("music", EN_US, Map.of("b", "two")));

        assertEquals("1", manager.getString("music:a"));
        assertEquals("two", manager.getString("music:b"));
    }

    @Test
    void languageOnlyLocaleFallsBackToARegionalVariant() {
        // fr -> fr-FR (bundled), and the same for runtime strings of a plugin namespace
        assertEquals("Cette commande est désactivée", manager.getString(FR, "commands.messages.disabled"));

        manager.registerNamespace("music");
        manager.loadLanguage("music", Locale.forLanguageTag("fr-CA"), Map.of("hello", "Allo"));
        assertEquals("Allo", manager.getString(FR, "music:hello"));
        assertEquals("Allo", manager.getString(FR_FR, "music:hello"), "any same-language variant is accepted");
    }

    @Test
    void unknownLocaleFallsBackToEnglish() {
        assertEquals("This command is disabled", manager.getString(DE_DE, "commands.messages.disabled"));
    }

    @Test
    void configuredDefaultLocaleIsTriedBeforeEnglish() {
        // Decision 2026-09-20: a bot configured fr-FR answers an unknown locale in French; en-US is the last net.
        SimpleLanguageManager frDefault = new SimpleLanguageManager(FR_FR);
        assertEquals("Cette commande est désactivée", frDefault.getString(DE_DE, "commands.messages.disabled"));
        assertEquals("This command is disabled", frDefault.getString(EN_US, "commands.messages.disabled"),
                "an explicit English request still gets English");

        frDefault.loadLanguage("core", EN_US, Map.of("only.english", "Only"));
        assertEquals("Only", frDefault.getString(DE_DE, "only.english"), "en-US still catches what the default lacks");
        frDefault.loadLanguage("core", FR_FR, Map.of("only.french", "Seulement"));
        assertEquals("Seulement", frDefault.getString(EN_US, "only.french"), "and the default catches what en-US lacks");
    }

    @Test
    void newLocalesBecomeAvailableWhenLoaded() {
        manager.loadLanguage("core", DE_DE, Map.of("k", "v"));
        assertTrue(manager.getAvailableLocales().containsKey("de-DE"));
        assertEquals("v", manager.getString(DE_DE, "k"));
    }

    // --- arguments ------------------------------------------------------------------------------

    @Test
    void argumentsUseMessageFormat() {
        assertEquals("This command is on cooldown. Please wait 3 second(s) before using it again.",
                manager.getString("commands.messages.cooldown", 3));
        assertEquals("Permission refusée : music.play", manager.getString(FR, "permissions.error.denied", "music.play"),
                "arguments go through the cascade too");
    }

    @Test
    void messageFormatQuirksAreCharacterized() {
        manager.loadLanguage("core", EN_US, Map.of(
                "quote", "It's {0}",
                "quoted", "It''s {0}",
                "big", "{0,number,#}"));

        assertEquals("Its {0}", manager.getString("quote", "x"), "a single quote swallows the placeholder");
        assertEquals("It's x", manager.getString("quoted", "x"));
        assertEquals("1234567", manager.getString("big", 1234567), "explicit number pattern avoids grouping");
    }

    @Test
    void formatFailureReturnsTheRawValue() {
        manager.loadLanguage("core", EN_US, Map.of("broken", "unbalanced {0"));
        assertEquals("unbalanced {0", manager.getString("broken", "x"));
    }

    // --- events ---------------------------------------------------------------------------------

    static class StubPlugin implements Plugin {
        private PluginLifecycle lifecycle = PluginLifecycle.LOADED;
        @Override public String getId() { return "test"; }
        @Override public String getName() { return "test"; }
        @Override public String getVersion() { return "1"; }
        @Override public void onLoad(PluginContext context) { }
        @Override public void onEnable() { }
        @Override public void onDisable() { }
        @Override public PluginLifecycle getLifecycle() { return lifecycle; }
        @Override public void setLifecycle(PluginLifecycle lifecycle) { this.lifecycle = lifecycle; }
    }

    static class Recorder {
        final List<StringRetrievalEvent> retrievals = new ArrayList<>();
        final List<String> namespaces = new ArrayList<>();
        final List<LanguageLoadedEvent> loads = new ArrayList<>();
        String override;

        @EventHandler public void on(StringRetrievalEvent e) { retrievals.add(e); if (override != null) e.setValue(override); }
        @EventHandler public void on(NamespaceRegisteredEvent e) { namespaces.add(e.getNamespace()); }
        @EventHandler public void on(LanguageLoadedEvent e) { loads.add(e); }
    }

    @Test
    void listenersCanOverrideAnyLookup() {
        SimpleEventManager events = new SimpleEventManager();
        Recorder recorder = new Recorder();
        events.registerListener(recorder, new StubPlugin());
        SimpleLanguageManager withEvents = new SimpleLanguageManager(EN_US, events);
        try {
            recorder.override = "Overridden {0}";
            assertEquals("Overridden {0}", withEvents.getString("commands.messages.disabled"));
            assertEquals("Overridden {0}", withEvents.getString("missing.key"), "misses are offered to listeners too");
            assertEquals("Overridden x", withEvents.getString("commands.messages.cooldown", "x"));
        } finally {
            events.shutdown();
        }
    }

    @Test
    void namespacesAreCaseInsensitive() {
        assertTrue(manager.registerNamespace("MyPlugin"));
        assertFalse(manager.registerNamespace("myplugin"), "same namespace");
        manager.loadLanguage("MYPLUGIN", EN_US, Map.of("hello", "Hi"));
        assertEquals("Hi", manager.getString("myplugin:hello"));
        assertEquals("Hi", manager.getString("MyPlugin:hello"), "the plugin id as typed by the plugin");
    }

    @Test
    void retrievalEventIsFiredOnceWithTheArguments() {
        SimpleEventManager events = new SimpleEventManager();
        Recorder recorder = new Recorder();
        events.registerListener(recorder, new StubPlugin());
        SimpleLanguageManager withEvents = new SimpleLanguageManager(EN_US, events);
        try {
            assertEquals(List.of("core"), recorder.namespaces);
            assertEquals(2, recorder.loads.size(), "en-US and fr-FR bundled resources");

            withEvents.getString("commands.messages.cooldown", 3);
            assertEquals(1, recorder.retrievals.size(), "one lookup, one event (was fired twice before L1)");
            assertArrayEquals(new Object[]{3}, recorder.retrievals.get(0).getArgs());
            assertEquals("commands.messages.cooldown", recorder.retrievals.get(0).getKey());
            assertEquals("core", recorder.retrievals.get(0).getNamespace());
            assertTrue(recorder.retrievals.get(0).getValue().contains("{0}"), "listeners see the raw pattern");

            recorder.retrievals.clear();
            withEvents.getString(DE_DE, "commands.messages.disabled");
            assertEquals(EN_US, recorder.retrievals.get(0).getLocale(), "the locale that actually answered");
        } finally {
            events.shutdown();
        }
    }
}
