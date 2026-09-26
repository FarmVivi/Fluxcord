package fr.farmvivi.fluxcord.api.plugin;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandRegistry;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorage;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.ScopedBinaryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The five per-plugin adapters, which exist for one reason: a plugin must not be able to reach another
 * plugin's keys, permissions, translations or files by accident.
 *
 * <p>Every test here asserts the namespaced value that reaches the shared service, not the call itself —
 * a façade that forwards the plain name would look perfectly healthy from the plugin's side and collide
 * silently with its neighbours. The plugin id is deliberately not the plugin *name*: two plugins can
 * share a display name.
 */
class PluginAdapterNamespacingTest {

    private static final String PLUGIN_ID = "music-plugin";

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getId()).thenReturn(PLUGIN_ID);
        when(plugin.getName()).thenReturn("Fluxcord Plugin - Music");
    }

    // Language

    @Test
    void translationKeysAreNamespacedByThePluginId() {
        LanguageManager manager = mock(LanguageManager.class);
        when(manager.getString(anyString())).thenReturn("hi");
        when(manager.getString(anyString(), any(Object[].class))).thenReturn("hi");
        when(manager.getString(any(Locale.class), anyString())).thenReturn("hi");
        when(manager.getString(any(Locale.class), anyString(), any(Object[].class))).thenReturn("hi");
        PluginLanguageAdapter language = new PluginLanguageAdapter(plugin, manager);

        language.getString("player.now_playing");
        language.getString("player.now_playing", "a");
        language.getString(Locale.FRANCE, "player.now_playing");
        language.getString(Locale.FRANCE, "player.now_playing", "a");

        verify(manager).getString(PLUGIN_ID + ":player.now_playing");
        verify(manager).getString(eq(PLUGIN_ID + ":player.now_playing"), any(Object[].class));
        verify(manager).getString(Locale.FRANCE, PLUGIN_ID + ":player.now_playing");
        verify(manager).getString(eq(Locale.FRANCE), eq(PLUGIN_ID + ":player.now_playing"),
                any(Object[].class));
    }

    @Test
    void theNamespaceIsRegisteredOnceWhenTheAdapterIsBuilt() {
        LanguageManager manager = mock(LanguageManager.class);

        new PluginLanguageAdapter(plugin, manager);

        verify(manager).registerNamespace(PLUGIN_ID);
    }

    @Test
    void aLookupThatMissesIsReportedInsteadOfPassingSilently() {
        // The manager signals a miss by echoing the key it was given. Left unreported, the user simply
        // reads 'music-plugin:player.now_playing' in Discord and nothing in the log says why.
        LanguageManager manager = mock(LanguageManager.class);
        when(manager.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        PluginLanguageAdapter language = new PluginLanguageAdapter(plugin, manager);

        String resolved = language.getString("player.missing");

        assertEquals(PLUGIN_ID + ":player.missing", resolved, "the miss is still returned as-is");
    }

    @Test
    void theAdapterExposesTheSharedManagerForWhatItCannotNamespace() {
        LanguageManager manager = mock(LanguageManager.class);
        when(manager.getDefaultLocale()).thenReturn(Locale.FRANCE);
        PluginLanguageAdapter language = new PluginLanguageAdapter(plugin, manager);

        assertSame(manager, language.getLanguageManager());
        assertEquals(Locale.FRANCE, language.getDefaultLocale());
    }

    // Permissions

    @Test
    void permissionChecksReachTheManagerUnderTheNameTheyWereRegisteredWith() {
        // Permissions are *not* prefixed by the adapter: plugins register fully qualified names
        // (<pluginId>.<node>) themselves, so the adapter must pass them through untouched. Prefixing
        // here would double the id and every check would fail.
        PermissionManager manager = mock(PermissionManager.class);
        PluginPermissionAdapter permissions =
                new PluginPermissionAdapter(plugin, manager, mock(LanguageManager.class));
        Permission permission = mock(Permission.class);
        when(permission.getName()).thenReturn(PLUGIN_ID + ".play");

        permissions.registerPermission(permission);
        permissions.hasPermission("u1", PLUGIN_ID + ".play");
        permissions.hasPermission("u1", "g1", PLUGIN_ID + ".play");

        verify(manager).registerPermission(permission, plugin);
        verify(manager).hasPermission("u1", PLUGIN_ID + ".play");
        verify(manager).hasPermission("u1", "g1", PLUGIN_ID + ".play");
    }

    @Test
    void aPluginOnlySeesThePermissionsItRegistered() {
        PermissionManager manager = mock(PermissionManager.class);
        PluginPermissionAdapter permissions =
                new PluginPermissionAdapter(plugin, manager, mock(LanguageManager.class));
        Permission play = mock(Permission.class);
        when(play.getName()).thenReturn(PLUGIN_ID + ".play");

        assertTrue(permissions.getRegisteredPermissions().isEmpty());
        permissions.registerPermission(play);

        assertEquals(Set.of(PLUGIN_ID + ".play"), permissions.getRegisteredPermissions());
    }

    // Commands

    @Test
    void commandsAreRegisteredAndUnregisteredOnBehalfOfTheirOwnPlugin() {
        CommandService service = mock(CommandService.class);
        PluginCommandAdapter commands = new PluginCommandAdapter(plugin, service);
        Command command = mock(Command.class);

        commands.registerCommand(command);
        commands.registerCommand(builder -> builder.name("play"));

        verify(service).registerCommand(command, plugin);
        verify(service).registerCommand(same(plugin), any());
        assertSame(service, commands.getCommandService());
    }

    @Test
    void aPluginCanOnlyUnregisterItsOwnCommands() {
        // Otherwise one plugin could unregister another plugin's /play by name.
        CommandRegistry registry = mock(CommandRegistry.class);
        CommandService service = mock(CommandService.class);
        when(service.getRegistry()).thenReturn(registry);
        Command mine = mock(Command.class);
        Command theirs = mock(Command.class);
        Plugin other = mock(Plugin.class);
        when(registry.getCommand("play")).thenReturn(Optional.of(mine));
        when(registry.getCommand("skip")).thenReturn(Optional.of(theirs));
        when(registry.getCommand("absent")).thenReturn(Optional.empty());
        when(registry.getPlugin(mine)).thenReturn(Optional.of(plugin));
        when(registry.getPlugin(theirs)).thenReturn(Optional.of(other));
        when(registry.unregister("play")).thenReturn(true);
        PluginCommandAdapter commands = new PluginCommandAdapter(plugin, service);

        assertTrue(commands.unregisterCommand("play"));
        assertFalse(commands.unregisterCommand("skip"), "another plugin's command");
        assertFalse(commands.unregisterCommand("absent"));
        verify(registry, never()).unregister("skip");
        verify(registry, never()).unregister("absent");
    }

    @Test
    void anUnownedCommandIsNotUnregisteredEither() {
        // A command the registry knows but attributes to nobody must not be removable by a plugin.
        CommandRegistry registry = mock(CommandRegistry.class);
        CommandService service = mock(CommandService.class);
        when(service.getRegistry()).thenReturn(registry);
        Command orphan = mock(Command.class);
        when(registry.getCommand("help")).thenReturn(Optional.of(orphan));
        when(registry.getPlugin(orphan)).thenReturn(Optional.empty());

        assertFalse(new PluginCommandAdapter(plugin, service).unregisterCommand("help"));

        verify(registry, never()).unregister(anyString());
    }

    @Test
    void theCommandListAndTheBulkRemovalAreScopedToThisPlugin() {
        CommandRegistry registry = mock(CommandRegistry.class);
        CommandService service = mock(CommandService.class);
        when(service.getRegistry()).thenReturn(registry);
        Command mine = mock(Command.class);
        when(registry.getCommands(plugin)).thenReturn(List.of(mine));
        when(registry.unregisterAll(plugin)).thenReturn(3);
        PluginCommandAdapter commands = new PluginCommandAdapter(plugin, service);

        assertEquals(List.of(mine), commands.getCommands());
        assertEquals(3, commands.unregisterAll());
        verify(registry).getCommands(plugin);
        verify(registry).unregisterAll(plugin);
    }

    // Data storage

    @Test
    void everyDataScopeIsNamespacedByThePluginId() {
        // Real ScopedStorage views over a mocked backend: what is asserted is the prefix each scope
        // really carries, not that some mock was returned.
        DataStorage backend = mock(DataStorage.class);
        DataStorageManager manager = mock(DataStorageManager.class);
        when(manager.getGlobalStorage()).thenReturn(new ScopedStorage(backend, StorageKey.globalScope()));
        when(manager.getUserStorage("u1"))
                .thenReturn(new ScopedStorage(backend, StorageKey.userScope("u1")));
        when(manager.getGuildStorage("g1"))
                .thenReturn(new ScopedStorage(backend, StorageKey.guildScope("g1")));
        when(manager.getUserGuildStorage("u1", "g1"))
                .thenReturn(new ScopedStorage(backend, StorageKey.userGuildScope("u1", "g1")));
        PluginDataStorageAdapter storage = new PluginDataStorageAdapter(plugin, manager);

        assertEquals(PLUGIN_ID + ".", storage.getGlobalStorage().getPrefix());
        assertEquals(PLUGIN_ID + ".", storage.getUserStorage("u1").getPrefix());
        assertEquals("guild:g1", storage.getGuildStorage("g1").getScope(), "the scope is untouched");
        assertEquals(PLUGIN_ID + ".", storage.getGuildStorage("g1").getPrefix());
        assertEquals("user:u1:guild:g1", storage.getUserGuildStorage("u1", "g1").getScope());

        // And a write really lands under the namespaced key.
        storage.getGuildStorage("g1").set("volume", 80);
        verify(backend).set(StorageKey.guild("g1", PLUGIN_ID + ".volume"), 80);
    }

    @Test
    void flushingFromAPluginFlushesTheWholeStorage() {
        // Documented on purpose: a plugin cannot flush only its own keys, and a test saying so keeps the
        // next reader from assuming otherwise.
        DataStorageManager manager = mock(DataStorageManager.class);
        when(manager.saveAll()).thenReturn(true);

        assertTrue(new PluginDataStorageAdapter(plugin, manager).saveAll());

        verify(manager).saveAll();
    }

    // Binary storage

    @Test
    void everyBinaryScopeIsNamespacedByThePluginId() {
        BinaryStorageManager manager = mock(BinaryStorageManager.class);
        BinaryStorage backend = mock(BinaryStorage.class);
        when(manager.getGlobalStorage()).thenReturn(new ScopedBinaryStorage(backend, "global"));
        when(manager.getUserStorage("u1")).thenReturn(new ScopedBinaryStorage(backend, "user:u1"));
        when(manager.getGuildStorage("g1")).thenReturn(new ScopedBinaryStorage(backend, "guild:g1"));
        when(manager.getUserGuildStorage("u1", "g1"))
                .thenReturn(new ScopedBinaryStorage(backend, "user:u1:guild:g1"));
        PluginBinaryStorageAdapter binary = new PluginBinaryStorageAdapter(plugin, manager);

        assertEquals(PLUGIN_ID + "/", binary.getGlobalStorage().getPrefix(),
                "binary namespaces are directories, so the separator is a slash and not a dot");
        assertEquals(PLUGIN_ID + "/", binary.getUserStorage("u1").getPrefix());
        assertEquals("guild:g1", binary.getGuildStorage("g1").getScope());
        assertEquals("user:u1:guild:g1", binary.getUserGuildStorage("u1", "g1").getScope());
    }
}
