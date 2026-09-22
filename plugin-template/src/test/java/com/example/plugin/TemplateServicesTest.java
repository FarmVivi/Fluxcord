package com.example.plugin;

import com.example.plugin.commands.ExampleCommand;
import com.example.plugin.services.ExampleDataService;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The two helper classes the template ships with: the command a plugin author edits first, and the
 * service that shows the three storage scopes.
 */
class TemplateServicesTest {

    private static final String PLUGIN_ID = "plugin-template";
    private static final String USER_ID = "u1";

    private AbstractPlugin plugin;
    private Configuration configuration;
    private PluginPermissionAdapter permissions;
    private PluginDataStorageAdapter storage;
    private ScopedStorage scoped;
    private CommandContext context;

    @BeforeEach
    void setUp() {
        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        permissions = mock(PluginPermissionAdapter.class);

        scoped = mock(ScopedStorage.class);
        storage = mock(PluginDataStorageAdapter.class);
        when(storage.getUserStorage(anyString())).thenReturn(scoped);
        when(storage.getGuildStorage(anyString())).thenReturn(scoped);
        when(storage.getGlobalStorage()).thenReturn(scoped);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));

        plugin = mock(AbstractPlugin.class);
        when(plugin.getId()).thenReturn(PLUGIN_ID);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("template-services-test"));
        when(plugin.getConfiguration()).thenReturn(configuration);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.getStorage()).thenReturn(storage);
        when(plugin.getLanguage()).thenReturn(language);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getName()).thenReturn("tester");
        context = mock(CommandContext.class);
        when(context.getUser()).thenReturn(user);
    }

    @Test
    void theCommandChecksThePermissionItsOwnPluginRegistered() {
        // TemplatePlugin registers "<plugin id>.use"; asking for anything else always refuses.
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".use")).thenReturn(true);

        assertTrue(new ExampleCommand(plugin).execute(context).isSuccess());

        verify(permissions).hasPermission(USER_ID, PLUGIN_ID + ".use");
        verify(context).reply("messages.example_message");
    }

    @Test
    void aUserWithoutThePermissionGetsTheLocalisedRefusal() {
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(false);

        assertFalse(new ExampleCommand(plugin).execute(context).isSuccess());

        verify(context).reply("errors.no_permission");
    }

    @Test
    void theCommandCanBeTurnedOffInTheConfiguration() {
        when(configuration.getBoolean("commands.enabled", true)).thenReturn(false);

        assertFalse(new ExampleCommand(plugin).execute(context).isSuccess());

        verifyNoInteractions(permissions);
        verify(context, never()).reply(anyString());
    }

    @Test
    void savingAPreferenceWritesToTheUserScopeAndFlushes() {
        new ExampleDataService(plugin).saveUserPreference(USER_ID, "theme", "dark");

        verify(storage).getUserStorage(USER_ID);
        verify(scoped).set("theme", "dark");
        verify(storage).saveAll();
    }

    @Test
    void storageCanBeDisabledWithoutBreakingTheCallers() {
        when(configuration.getBoolean("storage.enabled", true)).thenReturn(false);
        ExampleDataService service = new ExampleDataService(plugin);

        service.saveUserPreference(USER_ID, "theme", "dark");
        assertEquals("light", service.getUserPreference(USER_ID, "theme", "light"),
                "the default is returned instead of reading a disabled storage");

        verifyNoInteractions(scoped);
    }

    @Test
    void readingAPreferenceFallsBackToTheDefault() {
        when(scoped.get("theme", String.class)).thenReturn(Optional.empty());
        ExampleDataService service = new ExampleDataService(plugin);

        assertEquals("light", service.getUserPreference(USER_ID, "theme", "light"));

        when(scoped.get("theme", String.class)).thenReturn(Optional.of("dark"));
        assertEquals("dark", service.getUserPreference(USER_ID, "theme", "light"));
    }

    @Test
    void aStorageFailureIsLoggedRatherThanPropagated() {
        when(scoped.set(anyString(), any())).thenThrow(new IllegalStateException("backend down"));
        ExampleDataService service = new ExampleDataService(plugin);

        assertDoesNotThrow(() -> service.saveUserPreference(USER_ID, "theme", "dark"));
        assertDoesNotThrow(() -> service.updateGuildSetting("g1", "prefix", "!"));
        assertEquals("light", service.getUserPreference(USER_ID, "theme", "light"));
    }

    @Test
    void guildSettingsAreNamespacedUnderSettings() {
        new ExampleDataService(plugin).updateGuildSetting("g1", "prefix", "!");

        verify(storage).getGuildStorage("g1");
        verify(scoped).set("settings.prefix", "!");
    }

    @Test
    void theUsageCounterIncrementsAndOnlyFlushesEveryTenHits() {
        when(scoped.get("stats.starts", Long.class)).thenReturn(Optional.of(4L));
        ExampleDataService service = new ExampleDataService(plugin);

        service.incrementUsageCounter("starts");

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(scoped).set(eq("stats.starts"), value.capture());
        assertEquals(5L, value.getValue());
        verify(storage, never()).saveAll();

        when(scoped.get("stats.starts", Long.class)).thenReturn(Optional.of(10L));
        service.incrementUsageCounter("starts");
        verify(storage).saveAll();
    }

    @Test
    void cleanupFlushesWhateverIsPending() {
        new ExampleDataService(plugin).cleanup();

        verify(storage).saveAll();
    }
}
