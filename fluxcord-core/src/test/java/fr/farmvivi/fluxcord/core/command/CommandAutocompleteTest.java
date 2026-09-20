package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteProvider;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Slash-command autocomplete (plan item C3): the focused option's provider is called with the typing context
 * and its suggestions are sent back to Discord, capped to 25, never throwing.
 */
class CommandAutocompleteTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final SimpleCommandService service = new SimpleCommandService(events, mock(LanguageManager.class),
            mock(PermissionManager.class), mock(Configuration.class), mock(DataStorageManager.class), "!");

    @AfterEach
    void shutdown() {
        service.disable();
        events.shutdown();
    }

    private static Command command(String name, AutocompleteProvider<String> provider) {
        return new SimpleCommandBuilder().name(name).description("d")
                .stringOption("track", "track", true, provider)
                .stringOption("plain", "no autocomplete", false)
                .executor((ctx, cmd) -> CommandResult.success())
                .build();
    }

    @Test
    void providerReceivesTheTypingContext() {
        AtomicReference<AutocompleteContext> seen = new AtomicReference<>();
        service.getRegistry().register(command("play", ctx -> {
            seen.set(ctx);
            return List.of(new OptionChoice<>("Song " + ctx.partial(), "id-1"));
        }), null);

        List<OptionChoice<?>> choices = service.suggest("play", null, "track",
                new AutocompleteContext("so", "g1", "u1", Map.of("plain", "x")));

        assertEquals(1, choices.size());
        assertEquals("Song so", choices.get(0).name());
        assertEquals("g1", seen.get().guildId());
        assertEquals("u1", seen.get().userId());
        assertEquals("x", seen.get().option("plain"));
        assertNull(seen.get().option("missing"));
    }

    @Test
    void suggestionsAreCappedAtDiscordsLimit() {
        service.getRegistry().register(command("play", ctx -> IntStream.range(0, 40)
                .mapToObj(i -> new OptionChoice<>("c" + i, "v" + i)).toList()), null);

        assertEquals(25, service.suggest("play", null, "track", new AutocompleteContext("", null, "u", Map.of())).size());
    }

    @Test
    void unknownCommandOptionOrProviderGivesNothing() {
        service.getRegistry().register(command("play", ctx -> null), null);

        AutocompleteContext ctx = new AutocompleteContext("", null, "u", Map.of());
        assertTrue(service.suggest("nope", null, "track", ctx).isEmpty(), "unknown command");
        assertTrue(service.suggest("play", null, "nope", ctx).isEmpty(), "unknown option");
        assertTrue(service.suggest("play", null, "plain", ctx).isEmpty(), "option without provider");
        assertTrue(service.suggest("play", null, "track", ctx).isEmpty(), "provider returned null");
        assertTrue(service.suggest("play", "sub", "track", ctx).isEmpty(), "no such subcommand");
    }

    @Test
    void legacyPartialOnlyProvidersStillWork() {
        Command legacy = new SimpleCommandBuilder().name("old").description("d")
                .stringOption("q", "q", true, (String partial) -> List.of(new OptionChoice<>(partial.toUpperCase(), partial)))
                .executor((ctx, cmd) -> CommandResult.success()).build();
        service.getRegistry().register(legacy, null);

        List<OptionChoice<?>> choices = service.suggest("old", null, "q", new AutocompleteContext("ab", null, "u", Map.of()));
        assertEquals("AB", choices.get(0).name());
    }

    @Test
    void jdaEventIsAnsweredWithChoicesAndProviderFailuresAnswerEmpty() {
        service.getRegistry().register(command("play", ctx -> {
            if (ctx.partial().equals("boom")) {
                throw new IllegalStateException("provider bug");
            }
            return List.of(new OptionChoice<>("Hit", "h"));
        }), null);

        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        AutoCompleteQuery focused = mock(AutoCompleteQuery.class);
        User user = mock(User.class);
        Guild guild = mock(Guild.class);
        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.getName()).thenReturn("play");
        when(event.getFocusedOption()).thenReturn(focused);
        when(focused.getName()).thenReturn("track");
        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("u");
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("g");
        when(event.getOptions()).thenReturn(List.of());
        when(event.replyChoices(any(List.class))).thenReturn(action);

        when(focused.getValue()).thenReturn("hi");
        service.handleAutocomplete(event);
        ArgumentCaptor<List<net.dv8tion.jda.api.interactions.commands.Command.Choice>> captor = ArgumentCaptor.forClass(List.class);
        verify(event).replyChoices(captor.capture());
        assertEquals("Hit", captor.getValue().get(0).getName());
        assertEquals("h", captor.getValue().get(0).getAsString());

        when(focused.getValue()).thenReturn("boom");
        assertDoesNotThrow(() -> service.handleAutocomplete(event));
        verify(event, times(2)).replyChoices(captor.capture());
        assertTrue(captor.getValue().isEmpty(), "a failing provider answers an empty list");
        verify(action, times(2)).queue(any(), any());
    }
}
