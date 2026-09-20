package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Plan item C6: a command with subcommands routes to the selected subcommand on all three transports, with the
 * subcommand's own options, permission and cooldown. The parent (no executor) is never executed.
 */
class SubcommandRoutingTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final LanguageManager lang = mock(LanguageManager.class);
    private final PermissionManager permissions = mock(PermissionManager.class);
    private final JDA jda = mock(JDA.class);
    private final List<String> runs = new ArrayList<>();
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        when(lang.getString(any(), anyString(), any(Object[].class))).thenAnswer(inv -> inv.getArgument(1, String.class));
        when(lang.getString(any(), anyString())).thenAnswer(inv -> inv.getArgument(1, String.class));
        when(lang.getDefaultLocale()).thenReturn(Locale.US);
        when(permissions.hasPermission(anyString(), any(), anyString())).thenReturn(true);
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        service = new SimpleCommandService(events, lang, permissions, commands("!"), mock(DataStorageManager.class));
        service.setJDA(jda);
        service.enable();

        Command playlist = new SimpleCommandBuilder().name("playlist").description("p").permission("music.playlist")
                .subcommand(s -> s.name("add").description("a").stringOption("name", "n", true).stringOption("url", "u", false)
                        .executor((c, cmd) -> { runs.add("add:" + c.getOption("name", "?") + ":" + c.getOption("url", "-")); return CommandResult.success(); }))
                .subcommand(s -> s.name("remove").aliases("rm").description("r").stringOption("name", "n", true).permission("music.playlist.remove")
                        .executor((c, cmd) -> { runs.add("remove:" + c.getOption("name", "?")); return CommandResult.success(); }))
                .build();
        service.getRegistry().register(playlist, null);
    }

    @AfterEach
    void tearDown() {
        service.disable();
        events.shutdown();
    }

    @Test
    void slashRoutesBySubcommandNameAndReadsTheSubcommandOptions() {
        SlashCommandInteractionEvent event = slash("playlist", "add", "name", "chill", "url", "https://x");
        service.processCommand(event);
        assertEquals(List.of("add:chill:https://x"), runs);
    }

    @Test
    void slashWithoutASubcommandOnAParentIsAUsageError() {
        SlashCommandInteractionEvent event = slash("playlist", null);
        service.processCommand(event);
        assertTrue(runs.isEmpty());
        verify(event).reply(anyString());
    }

    @Test
    void textRoutesByTheNextTokenIncludingAliases() {
        service.processCommand(text("!playlist add chill"));
        service.processCommand(text("!playlist rm chill"));
        assertEquals(List.of("add:chill:-", "remove:chill"), runs);
    }

    @Test
    void textWithAnUnknownSubcommandIsAUsageError() {
        MessageReceivedEvent event = text("!playlist nope chill");
        service.processCommand(event);
        assertTrue(runs.isEmpty());
        verify(event.getMessage()).reply(any(MessageCreateData.class));
    }

    @Test
    void consoleRoutesLikeText() {
        service.processCommand(new ConsoleCommandEvent(jda, "playlist add chill"));
        service.processCommand(new ConsoleCommandEvent(jda, "PLAYLIST REMOVE chill"));
        assertEquals(List.of("add:chill:-", "remove:chill"), runs);
    }

    @Test
    void subcommandPermissionFallsBackToTheParentPermission() {
        when(permissions.hasPermission("u", null, "music.playlist")).thenReturn(false);
        service.processCommand(slash("playlist", "add", "name", "x"));
        assertTrue(runs.isEmpty(), "add has no permission of its own: the parent's applies");

        when(permissions.hasPermission("u", null, "music.playlist")).thenReturn(true);
        when(permissions.hasPermission("u", null, "music.playlist.remove")).thenReturn(false);
        service.processCommand(slash("playlist", "remove", "name", "x"));
        assertTrue(runs.isEmpty(), "remove has its own permission");
        service.processCommand(slash("playlist", "add", "name", "x"));
        assertEquals(List.of("add:x:-"), runs);
    }

    @Test
    void autocompleteAndCommandDataSeeTheSubcommands() {
        assertEquals("add", service.getRegistry().getCommand("playlist").orElseThrow().getSubcommands().get(0).getName());
        assertEquals("playlist/add", service.getRegistry().getCommand("playlist").orElseThrow().getSubcommands().get(0).getFullName());
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------

    private SlashCommandInteractionEvent slash(String name, String subcommand, String... options) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn("u");
        when(event.getName()).thenReturn(name);
        when(event.getSubcommandName()).thenReturn(subcommand);
        when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(null);
        when(event.getUserLocale()).thenReturn(DiscordLocale.ENGLISH_US);
        when(event.getOption(anyString())).thenReturn(null);
        for (int i = 0; i < options.length; i += 2) {
            OptionMapping mapping = mock(OptionMapping.class);
            when(mapping.getAsString()).thenReturn(options[i + 1]);
            when(event.getOption(options[i])).thenReturn(mapping);
        }
        when(event.reply(anyString())).thenReturn(mock(ReplyCallbackAction.class, RETURNS_SELF));
        return event;
    }

    private MessageReceivedEvent text(String content) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(user.getId()).thenReturn("u");
        when(user.isBot()).thenReturn(false);
        when(message.getContentRaw()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        when(event.getMessage()).thenReturn(message);
        when(event.getAuthor()).thenReturn(user);
        when(event.isFromGuild()).thenReturn(false);
        when(event.getChannel()).thenReturn(channel);
        when(message.reply(any(MessageCreateData.class))).thenReturn(mock(MessageCreateAction.class, RETURNS_SELF));
        return event;
    }

    private static CoreSettings.Commands commands(String prefix) {
        return new CoreSettings.Commands(prefix, false, false, false, false);
    }
}
