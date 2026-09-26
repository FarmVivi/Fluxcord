package fr.farmvivi.fluxcord.api.event;

import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.language.events.LanguageLoadedEvent;
import fr.farmvivi.fluxcord.api.language.events.StringRetrievalEvent;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PermissionDeniedException;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionChangeEvent;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionCheckEvent;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.events.StorageGetEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageRemoveEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageSetEvent;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The events a listener is allowed to interfere with, and what that interference means.
 *
 * <p>These are not plain data carriers: cancelling a storage read makes the listener's value the answer,
 * cancelling a permission check forces the result, and setting a string overrides a translation. The core
 * acts on those flags, so their defaults and their interaction are a contract — an event that arrived
 * pre-cancelled, or a value that counted as "overridden" before anyone touched it, would change behaviour
 * everywhere at once.
 */
class ApiEventContractsTest {

    @Test
    void anEventIsNeverCancelledUntilSomeoneCancelsIt() {
        StorageGetEvent read = new StorageGetEvent(StorageKey.guild("g1", "k"), String.class, null);
        StorageSetEvent write = new StorageSetEvent(StorageKey.guild("g1", "k"), "v");
        StorageRemoveEvent remove = new StorageRemoveEvent(StorageKey.guild("g1", "k"));
        PermissionCheckEvent check = new PermissionCheckEvent("u1", "g1", "music-plugin.play", false);

        assertFalse(read.isCancelled());
        assertFalse(write.isCancelled());
        assertFalse(remove.isCancelled());
        assertFalse(check.isCancelled());
    }

    @Test
    void cancellingIsReversible() {
        // The bus lets several listeners see the same event, so a later one must be able to un-cancel.
        StorageGetEvent event = new StorageGetEvent(StorageKey.global("k"), String.class, null);

        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
    }

    @Test
    void aStorageReadCarriesTheKeyTheTypeAndTheValueItFound() {
        StorageGetEvent event = new StorageGetEvent(StorageKey.user("u1", "theme"), String.class, "dark");

        assertEquals(StorageKey.user("u1", "theme"), event.getKey());
        assertEquals(String.class, event.getType());
        assertEquals("dark", event.getValue());
    }

    @Test
    void aListenerCanSubstituteTheValueOfAStorageRead() {
        // This is how a cache or a virtual key is implemented: cancel, and hand back your own value.
        StorageGetEvent event = new StorageGetEvent(StorageKey.global("k"), String.class, null);

        event.setValue("from a cache");
        event.setCancelled(true);

        assertEquals("from a cache", event.getValue());
        assertTrue(event.isCancelled());
    }

    @Test
    void aWriteCanBeRewrittenBeforeItReachesTheBackend() {
        StorageSetEvent event = new StorageSetEvent(StorageKey.guild("g1", "prefix"), "!");

        assertEquals("!", event.getValue());
        event.setValue("?");

        assertEquals("?", event.getValue());
    }

    @Test
    void aTranslationIsOnlyOverriddenOnceSomeoneSetsIt() {
        StringRetrievalEvent event = new StringRetrievalEvent(
                "music-plugin", Locale.FRANCE, "player.now_playing", new Object[]{"a song"}, "En lecture");

        assertFalse(event.isOverridden(), "reading the value is not overriding it");
        assertEquals("En lecture", event.getValue());
        assertEquals("music-plugin", event.getNamespace());
        assertEquals(Locale.FRANCE, event.getLocale());
        assertEquals("player.now_playing", event.getKey());
        assertArrayEquals(new Object[]{"a song"}, event.getArgs());

        event.setValue("Ça joue");

        assertTrue(event.isOverridden());
        assertEquals("Ça joue", event.getValue());
    }

    @Test
    void settingATranslationToTheSameTextStillCountsAsAnOverride() {
        // The flag records that a listener spoke, not that the text changed.
        StringRetrievalEvent event = new StringRetrievalEvent(
                "core", Locale.US, "k", null, "same");

        event.setValue("same");

        assertTrue(event.isOverridden());
    }

    @Test
    void aPermissionCheckCarriesItsAnswerAndCanBeForced() {
        PermissionCheckEvent event = new PermissionCheckEvent("u1", "g1", "music-plugin.play", false);

        assertEquals("u1", event.getUserId());
        assertEquals("g1", event.getGuildId());
        assertEquals("music-plugin.play", event.getPermission());
        assertFalse(event.getResult());

        event.setResult(true);
        event.setCancelled(true);

        assertTrue(event.getResult(), "cancelling makes this the answer, without touching storage");
    }

    @Test
    void aPermissionChangeCarriesBothSides() {
        PermissionChangeEvent event =
                new PermissionChangeEvent("u1", "g1", "music-plugin.play", false, true);

        assertEquals("u1", event.getUserId());
        assertEquals("music-plugin.play", event.getPermission());
        assertFalse(event.getOldValue());
        assertTrue(event.getNewValue());
    }

    @Test
    void aLanguageLoadCarriesWhatWasLoaded() {
        LanguageLoadedEvent event = new LanguageLoadedEvent("music-plugin", Locale.FRANCE, 2,
                Map.of("a", "b", "c", "d"));

        assertEquals("music-plugin", event.getNamespace());
        assertEquals(Locale.FRANCE, event.getLocale());
        assertEquals(Map.of("a", "b", "c", "d"), event.getTranslations());
    }

    @Test
    void eventTypeInfoKnowsWhetherItsEventCanBeCancelled() {
        // Used to describe the bus; getting it wrong would advertise a veto that does not exist.
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Music");

        EventTypeInfo cancellable = new EventTypeInfo(StorageGetEvent.class, plugin, "a storage read");
        EventTypeInfo plain = new EventTypeInfo(LanguageLoadedEvent.class, plugin, "a language load");

        assertTrue(cancellable.isCancellable());
        assertFalse(plain.isCancellable());
        assertEquals("StorageGetEvent", cancellable.getSimpleName());
        assertEquals(StorageGetEvent.class.getName(), cancellable.getFullName());
        assertEquals(StorageGetEvent.class, cancellable.getEventType());
        assertSame(plugin, cancellable.getPlugin());
        assertEquals("a storage read", cancellable.getDescription());
        assertTrue(cancellable.toString().contains("StorageGetEvent"));
    }

    @Test
    void aCommandResultSaysWhetherItSucceededAndWhyNot() {
        assertTrue(CommandResult.success().isSuccess());
        assertNull(CommandResult.success().getErrorMessage());
        assertTrue(CommandResult.success("done").isSuccess());
        assertFalse(CommandResult.error("nope").isSuccess());
        assertEquals("nope", CommandResult.error("nope").getErrorMessage());
    }

    @Test
    void aPermissionDenialNamesThePermissionItWanted() {
        PermissionDeniedException denied = new PermissionDeniedException(
                "You are not allowed to do that", "music-plugin.admin", "u1");

        assertEquals("You are not allowed to do that", denied.getMessage());
        assertEquals("music-plugin.admin", denied.getPermission());
        assertEquals("u1", denied.getUserId());

        PermissionDeniedException inGuild = new PermissionDeniedException(
                "nope", "music-plugin.admin", "u1", "g1");
        assertEquals("g1", inGuild.getGuildId(), "a guild-scoped denial says where");

        Exception cause = new IllegalStateException("backend down");
        assertSame(cause, new PermissionDeniedException("nope", cause, "p", "u1").getCause());
        assertSame(cause, new PermissionDeniedException("nope", cause, "p", "u1", "g1").getCause());
    }

    @Test
    void theFourPermissionDefaultsExistAndAreDistinct() {
        // OP and NOT_OP are resolved live against the operator list, TRUE/FALSE are constants.
        assertEquals(4, PermissionDefault.values().length);
        assertNotNull(PermissionDefault.valueOf("TRUE"));
        assertNotNull(PermissionDefault.valueOf("FALSE"));
        assertNotNull(PermissionDefault.valueOf("OP"));
        assertNotNull(PermissionDefault.valueOf("NOT_OP"));
    }

    @Test
    void theLifecycleGoesFromDiscoveredToDisabledAndHasAnErrorState() {
        assertEquals(PluginLifecycle.DISCOVERED, PluginLifecycle.values()[0]);
        assertNotNull(PluginLifecycle.valueOf("ENABLED"));
        assertNotNull(PluginLifecycle.valueOf("DISABLED"));
        assertNotNull(PluginLifecycle.valueOf("ERROR"), "a failed phase has somewhere to land");
    }
}
