package com.example.plugin;

import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLoader;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The template's lifecycle, phase by phase.
 *
 * <p>This is the file a plugin author reads to learn which phase does what, so the tests are written as
 * statements about the contract: permissions in {@code onPreEnable}, commands and listeners in
 * {@code onEnable}, cross-plugin work in {@code onPostEnable}, and every feature switchable off without
 * leaving a half-built plugin behind.
 */
class TemplateLifecycleTest {

    private static final String PLUGIN_ID = "plugin-template";

    @TempDir Path dataFolder;

    private TemplatePlugin plugin;
    private PluginContext context;
    private Configuration configuration;
    private PluginPermissionAdapter permissions;
    private PluginCommandAdapter commands;
    private EventManager eventManager;
    private DiscordAPI discordAPI;
    private PluginLoader pluginLoader;

    @BeforeEach
    void setUp() {
        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));

        permissions = mock(PluginPermissionAdapter.class);
        when(permissions.getRegisteredPermissions()).thenReturn(java.util.Set.of());
        commands = mock(PluginCommandAdapter.class);
        eventManager = mock(EventManager.class);
        discordAPI = mock(DiscordAPI.class);
        pluginLoader = mock(PluginLoader.class);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));

        ScopedStorage scoped = mock(ScopedStorage.class);
        PluginDataStorageAdapter storage = mock(PluginDataStorageAdapter.class);
        when(storage.getUserStorage(anyString())).thenReturn(scoped);
        when(storage.getGuildStorage(anyString())).thenReturn(scoped);
        when(storage.getGlobalStorage()).thenReturn(scoped);

        context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn(PLUGIN_ID);
        when(context.getPluginName()).thenReturn("Template Plugin");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("template-lifecycle-test"));
        when(context.getEventManager()).thenReturn(eventManager);
        when(context.getDiscordAPI()).thenReturn(discordAPI);
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getCommands()).thenReturn(commands);
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(storage);
        when(context.getPluginLoader()).thenReturn(pluginLoader);

        plugin = new TemplatePlugin();
    }

    /**
     * Switches the three example features on.
     *
     * <p>They ship off: a fresh copy of the template must register nothing. Every test that wants to see a
     * command or a listener therefore has to ask for it, which is also what a plugin author does.
     */
    private void withEveryFeatureOn() {
        when(configuration.getBoolean("features.example_commands", false)).thenReturn(true);
        when(configuration.getBoolean("features.example_events", false)).thenReturn(true);
        when(configuration.getBoolean("features.example_storage", false)).thenReturn(true);
    }

    /** Runs the enabling phases in the order the core does. */
    private void enable() {
        plugin.onLoad(context);
        plugin.onPreEnable();
        plugin.onEnable();
        plugin.onPostEnable();
    }

    private Map<String, PermissionDefault> declaredPermissions() {
        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());
        Map<String, PermissionDefault> declared = new HashMap<>();
        captor.getAllValues().forEach(p -> declared.put(p.getName(), p.getDefault()));
        return declared;
    }

    private List<String> registeredCommandNames() {
        ArgumentCaptor<Consumer<CommandBuilder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(commands, atLeastOnce()).registerCommand(captor.capture());
        List<String> names = new java.util.ArrayList<>();
        for (Consumer<CommandBuilder> registration : captor.getAllValues()) {
            CommandBuilder builder = mock(CommandBuilder.class, invocation ->
                    CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                            ? invocation.getMock() : null);
            registration.accept(builder);
            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(builder).name(name.capture());
            names.add(name.getValue());
        }
        return names;
    }

    @Test
    void permissionsAreDeclaredInPreEnableAndNamespacedByThePluginId() {
        withEveryFeatureOn();
        // onPreEnable is the phase for this: a command registered in onEnable may name a permission, and
        // the core resolves it against what was registered before.
        plugin.onLoad(context);
        plugin.onPreEnable();

        assertEquals(Map.of(
                PLUGIN_ID + ".use", PermissionDefault.TRUE,
                PLUGIN_ID + ".admin", PermissionDefault.OP,
                PLUGIN_ID + ".command.example", PermissionDefault.TRUE), declaredPermissions());
    }

    @Test
    void theCommandPermissionIsOnlyDeclaredWhenCommandsAre() {
        // The default, in fact: the template ships with every example off.

        plugin.onLoad(context);
        plugin.onPreEnable();

        assertFalse(declaredPermissions().containsKey(PLUGIN_ID + ".command.example"),
                "declaring a permission for a feature that is off would list a node nothing checks");
    }

    @Test
    void theExampleCommandIsRegisteredInEnable() {
        withEveryFeatureOn();
        enable();

        assertEquals(List.of("template-example"), registeredCommandNames());
    }

    @Test
    void bothEventBusesAreUsedForTheOneListener() {
        withEveryFeatureOn();
        // The template's point: Fluxcord events go through the event manager, Discord events through a JDA
        // listener. The same object serves both, and both registrations are needed.
        enable();

        verify(eventManager).registerListener(any(), same(plugin));
        verify(discordAPI).addEventListeners(same(plugin), any(Object[].class));
    }

    @Test
    void turningCommandsOffRegistersNoCommandAndStillEnables() {
        withEveryFeatureOn();
        when(configuration.getBoolean("features.example_commands", false)).thenReturn(false);

        assertDoesNotThrow(this::enable);

        verify(commands, never()).registerCommand(any(Consumer.class));
        verify(eventManager).registerListener(any(), same(plugin)); // the other features still run
    }

    @Test
    void turningEventsOffRegistersNoListener() {
        withEveryFeatureOn();
        when(configuration.getBoolean("features.example_events", false)).thenReturn(false);

        enable();

        verify(eventManager, never()).registerListener(any(), any());
        verify(discordAPI, never()).addEventListeners(any(), any(Object[].class));
    }

    @Test
    void turningEveryFeatureOffLeavesAPluginThatStillStartsAndStops() {
        // This is a fresh copy of the template, straight out of the box.

        assertDoesNotThrow(() -> {
            enable();
            plugin.onPreDisable();
            plugin.onDisable();
            plugin.onPostDisable();
        });
    }

    @Test
    void postEnableLooksForTheOtherPluginsByTheirId() {
        withEveryFeatureOn();
        // getPlugin takes the plugin id from plugin.yml. The template used to ask for "MusicPlugin", a name
        // no registry ever holds, so the integration branch could not fire.
        enable();

        verify(pluginLoader).getPlugin("music-plugin");
        verify(pluginLoader).getPlugin("ai-audio-plugin");
    }

    @Test
    void anIntegrationIsReportedWhenThePluginIsThere() {
        withEveryFeatureOn();
        when(pluginLoader.getPlugin("music-plugin")).thenReturn(mock(Plugin.class));

        assertDoesNotThrow(this::enable);

        verify(pluginLoader).getPlugin("music-plugin");
    }

    @Test
    void theIntegrationCheckCanBeTurnedOff() {
        withEveryFeatureOn();
        when(configuration.getBoolean("integration.check_other_plugins", true)).thenReturn(false);

        enable();

        verifyNoInteractions(pluginLoader);
    }

    @Test
    void migrationsRunOnlyWhenStorageIsOn() {

        enable();

        verify(configuration, never()).getInt(eq("config_version"), anyInt());
    }

    @Test
    void anUpToDateConfigurationIsNotRewritten() {
        withEveryFeatureOn();
        when(configuration.getInt("config_version", 1)).thenReturn(1);

        enable();

        verify(configuration, never()).set(anyString(), any());
    }

    @Test
    void aFailureDuringMigrationIsLoggedRatherThanStoppingTheBoot() {
        withEveryFeatureOn();
        // A plugin that throws in onPostEnable is dropped by the core; a migration is not worth that.
        when(configuration.getInt("config_version", 1)).thenThrow(new IllegalStateException("unreadable"));

        assertDoesNotThrow(this::enable);
    }

    @Test
    void disablingReleasesTheServicesAndIsSafeTwice() {
        withEveryFeatureOn();
        enable();

        assertDoesNotThrow(() -> {
            plugin.onPreDisable();
            plugin.onDisable();
            plugin.onPostDisable();
            plugin.onDisable();
        });
    }
}
