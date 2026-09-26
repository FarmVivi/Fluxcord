package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.core.testing.StubPlugin;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code perm} command from the console (trusted, no operator check) against a real permission
 * manager and an in-memory storage.
 */
class PermCommandTest {

    static class MemoryStorage implements DataStorage {
        final Map<String, Map<String, Object>> data = new HashMap<>();
        private Map<String, Object> scope(String scope) { return data.computeIfAbsent(scope, s -> new HashMap<>()); }
        @Override public <T> Optional<T> get(StorageKey key, Class<T> type) {
            Object v = scope(key.getScope()).get(key.getKey());
            return Optional.ofNullable(type.isInstance(v) ? type.cast(v) : null);
        }
        @Override public <T> boolean set(StorageKey key, T value) { scope(key.getScope()).put(key.getKey(), value); return true; }
        @Override public boolean exists(StorageKey key) { return scope(key.getScope()).containsKey(key.getKey()); }
        @Override public boolean remove(StorageKey key) { return scope(key.getScope()).remove(key.getKey()) != null; }
        @Override public Set<String> getKeys(String scope) { return new HashSet<>(scope(scope).keySet()); }
        @Override public Map<String, Object> getAll(String scope) { return new HashMap<>(scope(scope)); }
        @Override public boolean clear(String scope) { data.remove(scope); return true; }
        @Override public boolean save() { return true; }
        @Override public boolean close() { return true; }
    }

    record Perm(String getName, String getDescription, PermissionDefault getDefault) implements Permission { }

    private final SimpleEventManager events = new SimpleEventManager();
    private final JDA jda = mock(JDA.class);
    private final MemoryStorage storage = new MemoryStorage();
    private final SimplePermissionManager permissions = new SimplePermissionManager(events, new SimpleDataStorageManager(storage));
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        User user = mock(User.class);
        when(user.getId()).thenReturn("123456789012345678");
        when(jda.getUserById("123456789012345678")).thenReturn(user); // the console resolves USER options through JDA
        permissions.registerPermission(new Perm("music.play", "Play music", PermissionDefault.TRUE), new StubPlugin("music"));
        permissions.registerPermission(new Perm("music.volume", "Change the volume", PermissionDefault.OP), new StubPlugin("music"));
        service = new SimpleCommandService(events, new SimpleLanguageManager(Locale.US), permissions,
                new CoreSettings.Commands("!", false, false, false, true), new SimpleDataStorageManager(storage));
        service.setJDA(jda);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        service.disable();
        events.shutdown();
    }

    private String run(String line) {
        console.reset();
        service.processCommand(new ConsoleCommandEvent(jda, line));
        return console.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void nodesListsRegisteredPermissionsWithTheirDefaults() {
        String out = run("perm nodes");
        assertTrue(out.contains("`music.play` (TRUE) - Play music"), out);
        assertTrue(out.contains("`music.volume` (OP) - Change the volume"), out);
        assertTrue(run("perm").contains("Invalid usage"), "the action is a required option");
    }

    @Test
    void setUnsetAndListGlobalOverridesFromTheConsole() {
        assertFalse(permissions.hasPermission("123456789012345678", "music.volume"), "OP default, 123456789012345678 is nobody");

        String set = run("perm set music.volume 123456789012345678 true");
        assertTrue(set.contains("music.volume") && set.contains("123456789012345678") && set.contains("global"), set);
        assertTrue(permissions.hasPermission("123456789012345678", "music.volume"));
        assertEquals(true, storage.get(StorageKey.user("123456789012345678", "permission.music.volume"), Boolean.class).orElseThrow(), "persisted");

        String list = run("perm list 123456789012345678");
        assertTrue(list.contains("music.volume=true"), list);
        assertTrue(list.contains("music.play=true"), "effective values: " + list);

        String unset = run("perm unset music.volume 123456789012345678");
        assertTrue(unset.contains("music.volume"), unset);
        assertFalse(permissions.hasPermission("123456789012345678", "music.volume"));
        assertTrue(run("perm unset music.volume 123456789012345678").contains("music.volume"), "nothing to remove is still a success reply");
    }

    @Test
    void usageErrorsAndGuildScopeNeedAGuild() {
        assertTrue(run("perm set").contains("Usage"), "missing everything");
        assertTrue(run("perm set music.volume notanid true").toLowerCase().contains("usage"), "user must be a Discord id (the token slides to the scope choice and fails validation)");
        assertTrue(run("perm unset music.volume").contains("Usage"));
        assertTrue(run("perm list").contains("Usage"));
        assertTrue(run("perm set music.volume 123456789012345678 true guild").toLowerCase().contains("server"), "console has no guild");
    }

    @Test
    void permissionNodesAreAutocompleted() {
        var provider = service.getRegistry().getCommand("perm").orElseThrow().getOptions().stream()
                .filter(o -> o.getName().equals("permission")).findFirst().orElseThrow().getAutocompleteProvider();
        assertEquals(List.of("music.play", "music.volume"),
                provider.suggest(new AutocompleteContext("", null, "u", Map.of())).stream().map(c -> c.name()).toList());
        assertEquals(List.of("music.volume"),
                provider.suggest(new AutocompleteContext("VOL", null, "u", Map.of())).stream().map(c -> c.name()).toList());
    }
}
