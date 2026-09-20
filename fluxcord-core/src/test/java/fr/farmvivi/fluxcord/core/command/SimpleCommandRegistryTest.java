package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the flat, global command namespace: names and aliases are case-insensitive,
 * first registration wins, and every index stays consistent through unregister/enable/disable.
 */
class SimpleCommandRegistryTest {

    static class StubPlugin implements Plugin {
        private final String id;
        private PluginLifecycle lifecycle = PluginLifecycle.LOADED;

        StubPlugin(String id) { this.id = id; }

        @Override public String getId() { return id; }
        @Override public String getName() { return id; }
        @Override public String getVersion() { return "1"; }
        @Override public void onLoad(PluginContext context) { }
        @Override public void onEnable() { }
        @Override public void onDisable() { }
        @Override public PluginLifecycle getLifecycle() { return lifecycle; }
        @Override public void setLifecycle(PluginLifecycle lifecycle) { this.lifecycle = lifecycle; }
    }

    private static SimpleCommand command(String name, String category, boolean enabled, String... aliases) {
        return new SimpleCommand(name, "desc", category, null, null, null, null, null,
                Set.of(aliases), false, null, false, null, enabled, 0, (ctx, cmd) -> CommandResult.success());
    }

    private static SimpleCommand command(String name, String... aliases) {
        return command(name, null, true, aliases);
    }

    private final SimpleCommandRegistry registry = new SimpleCommandRegistry();
    private final StubPlugin plugin = new StubPlugin("a");

    @Test
    void registersNameAliasesAndOwner() {
        SimpleCommand play = command("Play", "p", "PL");

        assertTrue(registry.register(play, plugin));

        assertSame(play, registry.getCommand("play").orElseThrow());
        assertSame(play, registry.getCommand("PLAY").orElseThrow(), "names are case-insensitive");
        assertSame(play, registry.getCommandByAlias("P").orElseThrow());
        assertSame(play, registry.getCommandByAlias("pl").orElseThrow());
        assertTrue(registry.getCommandByAlias("play").isEmpty(), "the name itself is not an alias");
        assertEquals(Set.of("play"), Set.copyOf(registry.getCommandNames()));
        assertEquals(Set.of("p", "pl"), Set.copyOf(registry.getCommandAliases()));
        assertSame(plugin, registry.getPlugin(play).orElseThrow());
        assertSame(plugin, registry.getPlugin("play").orElseThrow());
        assertEquals(List.of(play), registry.getCommands(plugin));
        assertTrue(registry.getCommands(null).isEmpty(), "not a system command");
    }

    @Test
    void namesAreGlobalAndFirstRegistrationWins() {
        SimpleCommand first = command("play");
        SimpleCommand second = command("PLAY");
        StubPlugin other = new StubPlugin("b");

        assertTrue(registry.register(first, plugin));
        assertFalse(registry.register(second, other));

        assertSame(first, registry.getCommand("play").orElseThrow());
        assertTrue(registry.getCommands(other).isEmpty());
        assertTrue(registry.getPlugin(second).isEmpty());
    }

    @Test
    void aliasCollisionKeepsTheFirstOwnerEvenAfterTheSecondIsUnregistered() {
        SimpleCommand first = command("play", "p");
        SimpleCommand second = command("pause", "p");
        registry.register(first, plugin);
        registry.register(second, plugin); // alias 'p' already taken -> skipped, command still registered

        assertSame(first, registry.getCommandByAlias("p").orElseThrow());
        assertSame(second, registry.getCommand("pause").orElseThrow());

        assertTrue(registry.unregister("pause"));
        assertSame(first, registry.getCommandByAlias("p").orElseThrow(),
                "unregistering 'pause' must not steal the alias owned by 'play'");
    }

    @Test
    void unregisterAllOfAPluginKeepsOtherOwnersAliases() {
        StubPlugin other = new StubPlugin("b");
        SimpleCommand mine = command("play", "p");
        SimpleCommand theirs = command("pause", "p");
        registry.register(mine, plugin);
        registry.register(theirs, other);

        assertEquals(1, registry.unregisterAll(other));

        assertTrue(registry.getCommand("pause").isEmpty());
        assertSame(mine, registry.getCommandByAlias("p").orElseThrow());
        assertEquals(0, registry.unregisterAll(other));
    }

    @Test
    void unregisterCleansEveryIndex() {
        SimpleCommand play = command("play", "p");
        registry.register(play, plugin);

        assertTrue(registry.unregister("PLAY"));
        assertFalse(registry.unregister("play"));

        assertTrue(registry.getCommand("play").isEmpty());
        assertTrue(registry.getCommandByAlias("p").isEmpty());
        assertTrue(registry.getPlugin(play).isEmpty());
        assertTrue(registry.getCommands(plugin).isEmpty());
        assertTrue(registry.getCommandNames().isEmpty());
        assertTrue(registry.getCommandAliases().isEmpty());
    }

    @Test
    void systemCommandsHaveNoPlugin() {
        SimpleCommand help = command("help", "h");
        SimpleCommand version = command("version");
        registry.register(help, null);
        registry.register(version, null);
        registry.register(command("play"), plugin);

        assertEquals(List.of(help, version), registry.getCommands(null));
        assertTrue(registry.getPlugin("help").isEmpty());
        assertEquals(3, registry.getCommands().size());

        assertEquals(2, registry.unregisterAll(null));
        assertTrue(registry.getCommands(null).isEmpty());
        assertTrue(registry.getCommandByAlias("h").isEmpty());
        assertEquals(1, registry.getCommands().size());
    }

    @Test
    void categoriesAreCaseInsensitiveAndDefaultToGeneral() {
        registry.register(command("a", "Music", true), plugin);
        registry.register(command("b", "music", true), plugin);
        registry.register(command("c", null, true), plugin);

        assertEquals(Set.of("music", "general"), Set.copyOf(registry.getCategories()));
        assertEquals(2, registry.getCommandsByCategory("MUSIC").size());
        assertEquals(1, registry.getCommandsByCategory("general").size());
        assertTrue(registry.getCommandsByCategory("nope").isEmpty());
    }

    @Test
    void disableAndEnableReplaceTheCommandInPlace() {
        SimpleCommand play = command("play", "p");
        registry.register(play, plugin);

        assertFalse(registry.enableCommand("play"), "already enabled");
        assertTrue(registry.disableCommand("play"));
        assertFalse(registry.disableCommand("play"), "already disabled");

        Command disabled = registry.getCommand("play").orElseThrow();
        assertNotSame(play, disabled);
        assertFalse(disabled.isEnabled());
        assertEquals(Set.of("p"), disabled.getAliases());
        assertSame(disabled, registry.getCommandByAlias("p").orElseThrow());
        assertSame(plugin, registry.getPlugin(disabled).orElseThrow());
        assertEquals(List.of(disabled), registry.getCommands(plugin));

        assertTrue(registry.enableCommand("play"));
        assertTrue(registry.getCommand("play").orElseThrow().isEnabled());
        assertFalse(registry.enableCommand("missing"));
        assertFalse(registry.disableCommand("missing"));
    }

    @Test
    void disableWorksForSystemCommands() {
        registry.register(command("help"), null);

        assertTrue(registry.disableCommand("help"));

        Command disabled = registry.getCommand("help").orElseThrow();
        assertFalse(disabled.isEnabled());
        assertEquals(List.of(disabled), registry.getCommands(null));
    }

    @Test
    void viewsAreReadOnly() {
        registry.register(command("play"), plugin);

        assertThrows(UnsupportedOperationException.class, () -> registry.getCommands().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.getCommands(plugin).clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.getCommandNames().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.getCommandAliases().clear());
    }

    @Test
    void nullCommandIsRejected() {
        assertThrows(NullPointerException.class, () -> registry.register(null, plugin));
    }
}
