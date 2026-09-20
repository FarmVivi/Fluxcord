package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Slash-command synchronization with Discord: what goes to the global set, what goes to each guild, when the
 * service syncs on its own (boot, then debounced after runtime registrations) and when it stays quiet.
 */
class CommandSynchronizationTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final JDA jda = mock(JDA.class);
    private final CommandListUpdateAction globalUpdate = mock(CommandListUpdateAction.class);
    private final Guild guild = mock(Guild.class);
    private final CommandListUpdateAction guildUpdate = mock(CommandListUpdateAction.class);
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp;
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        LanguageManager lang = mock(LanguageManager.class);
        when(lang.getDefaultLocale()).thenReturn(Locale.US);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.updateCommands()).thenReturn(globalUpdate);
        when(globalUpdate.addCommands(anyCollection())).thenReturn(globalUpdate);
        when(globalUpdate.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(guild.getId()).thenReturn("42");
        when(guild.getName()).thenReturn("dev");
        when(guild.updateCommands()).thenReturn(guildUpdate);
        when(guildUpdate.addCommands(anyCollection())).thenReturn(guildUpdate);
        when(guildUpdate.submit()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(jda.getGuildById("42")).thenReturn(guild);

        service = new SimpleCommandService(events, lang, mock(PermissionManager.class),
                new CoreSettings.Commands("!", false, false, false, false), mock(DataStorageManager.class));
        service.setJDA(jda);
    }

    @AfterEach
    void tearDown() {
        service.disable();
        events.shutdown();
    }

    @Test
    void enableSyncsOnceGlobalCommandsAndEachGuildScopedSet() {
        register("ping", b -> b.executor((c, x) -> null));
        register("off", b -> b.enabled(false).executor((c, x) -> null));
        register("parent", b -> b.subcommand(s -> s.name("sub").description("s").executor((c, x) -> null)));
        register("local", b -> b.guilds("42").executor((c, x) -> null));
        register("elsewhere", b -> b.guilds("99").executor((c, x) -> null)); // guild the bot is not in

        service.enable();

        assertEquals(List.of("parent", "ping"), sent(globalUpdate), "enabled, global, top-level commands only");
        assertEquals(List.of("local"), sent(guildUpdate));
        verify(guildUpdate, times(1)).submit(); // guild 99 is unknown to JDA: skipped
        verify(globalUpdate, times(1)).submit();
    }

    @Test
    void registrationsAfterBootAreSyncedOnceThroughTheDebouncer() {
        service.enable();
        verify(globalUpdate, times(1)).submit();

        register("a", b -> b.executor((c, x) -> null));
        register("b", b -> b.executor((c, x) -> null));
        register("c", b -> b.executor((c, x) -> null));

        verify(globalUpdate, timeout(3000).times(2)).submit(); // one more sync, not three
        assertEquals(List.of("a", "b", "c"), sent(globalUpdate));
    }

    @Test
    void nothingIsSentWhileDisabledOrDisconnected() {
        register("ping", b -> b.executor((c, x) -> null));
        assertTrue(service.synchronizeCommands().isDone(), "disabled: completed no-op");
        verify(jda, never()).updateCommands();

        when(jda.getStatus()).thenReturn(JDA.Status.RECONNECT_QUEUED);
        service.enable();
        assertTrue(service.synchronizeGlobalCommands().isDone());
        assertTrue(service.synchronizeGuildCommands(guild).isDone());
        verify(jda, never()).updateCommands();
        verify(guild, never()).updateCommands();
    }

    private void register(String name, Consumer<CommandBuilder> customize) {
        CommandBuilder builder = new SimpleCommandBuilder().name(name).description("d");
        customize.accept(builder);
        service.registerCommand(builder.build());
    }

    @SuppressWarnings("unchecked")
    private static List<String> sent(CommandListUpdateAction action) {
        ArgumentCaptor<Collection<CommandData>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(action, atLeastOnce()).addCommands(captor.capture());
        return captor.getValue().stream().map(CommandData::getName).sorted().toList();
    }

    @Test
    void perGuildPrefixIsPersistedAndTheGlobalOneIsRuntimeOnly() {
        fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager storage =
                new fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager(new fr.farmvivi.fluxcord.core.storage.file.FileDataStorage(tmp.toFile(), events, 0));
        LanguageManager lang = mock(LanguageManager.class);
        SimpleCommandService withStorage = new SimpleCommandService(events, lang, mock(PermissionManager.class),
                new CoreSettings.Commands("!", false, false, false, false), storage);

        assertEquals("!", withStorage.getPrefix("g1"), "default until a guild override exists");
        withStorage.setPrefix("g1", "?");
        assertEquals("?", withStorage.getPrefix("g1"));
        assertEquals("?", storage.getGuildStorage("g1").get("commands.prefix", String.class).orElseThrow(), "persisted");
        assertEquals("!", withStorage.getPrefix("g2"));

        withStorage.setPrefix(null, "$");
        assertEquals("$", withStorage.getPrefix());
        assertEquals("$", withStorage.getPrefix((String) null));
        assertThrows(IllegalArgumentException.class, () -> withStorage.setPrefix("g1", ""));
        assertThrows(IllegalArgumentException.class, () -> withStorage.setPrefix(null));
        storage.close();
    }

    @Test
    void theJdaListenerForwardsHumansOnlyAndEveryInteraction() {
        SimpleCommandService target = mock(SimpleCommandService.class);
        fr.farmvivi.fluxcord.core.command.listener.CommandListener listener =
                new fr.farmvivi.fluxcord.core.command.listener.CommandListener(target);
        net.dv8tion.jda.api.events.message.MessageReceivedEvent bot = mock(net.dv8tion.jda.api.events.message.MessageReceivedEvent.class);
        net.dv8tion.jda.api.entities.User botUser = mock(net.dv8tion.jda.api.entities.User.class);
        when(botUser.isBot()).thenReturn(true);
        when(bot.getAuthor()).thenReturn(botUser);
        listener.onMessageReceived(bot);
        verify(target, never()).processCommand(any());

        net.dv8tion.jda.api.events.message.MessageReceivedEvent human = mock(net.dv8tion.jda.api.events.message.MessageReceivedEvent.class);
        net.dv8tion.jda.api.entities.User user = mock(net.dv8tion.jda.api.entities.User.class);
        when(human.getAuthor()).thenReturn(user);
        listener.onMessageReceived(human);
        verify(target).processCommand(human);

        net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent slash =
                mock(net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent.class);
        listener.onSlashCommandInteraction(slash);
        verify(target).processCommand(slash);
        net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent auto =
                mock(net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent.class);
        listener.onCommandAutoCompleteInteraction(auto);
        verify(target).handleAutocomplete(auto);
    }
}
