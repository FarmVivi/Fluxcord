package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Slash interactions: locale resolution, subcommand routing and the per-type reading of JDA option mappings. */
class SlashCommandParserTest {

    private final LanguageManager lang = mock(LanguageManager.class);
    private final SlashCommandParser parser = new SlashCommandParser(lang);
    private final SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);

    @BeforeEach
    void setUp() {
        when(lang.getDefaultLocale()).thenReturn(Locale.FRANCE);
        when(event.getUser()).thenReturn(mock(User.class));
        when(event.getGuild()).thenReturn(mock(Guild.class));
        when(event.getChannel()).thenReturn(mock(MessageChannelUnion.class));
        when(event.getUserLocale()).thenReturn(DiscordLocale.GERMAN);
        when(event.getName()).thenReturn("cmd");
    }

    private static Command command(Consumer<CommandBuilder> options) {
        CommandBuilder builder = new SimpleCommandBuilder().name("cmd").description("d");
        options.accept(builder);
        return builder.executor((c, cmd) -> CommandResult.success()).build();
    }

    private OptionMapping option(String name) {
        OptionMapping mapping = mock(OptionMapping.class);
        when(event.getOption(name)).thenReturn(mapping);
        return mapping;
    }

    @Test
    void onlySlashInteractionsAreAccepted() throws Exception {
        assertTrue(parser.canParse(event));
        assertTrue(parser.isCommandInvocation(event));
        assertEquals("cmd", parser.extractCommandName(event));
        MessageReceivedEvent other = mock(MessageReceivedEvent.class);
        assertFalse(parser.canParse(other));
        assertThrows(CommandParseException.class, () -> parser.extractCommandName(other));
        assertThrows(CommandParseException.class, () -> parser.parse(other, command(b -> {})));
    }

    @Test
    void localeComesFromTheUserAndFallsBackToTheDefault() throws Exception {
        assertEquals(Locale.GERMAN, parser.parse(event, command(b -> {})).getLocale());

        when(event.getUserLocale()).thenReturn(DiscordLocale.UNKNOWN);
        assertEquals(Locale.FRANCE, parser.parse(event, command(b -> {})).getLocale());
    }

    @Test
    void everyOptionTypeIsReadWithTheMatchingMappingAccessor() throws Exception {
        Command command = command(b -> b.stringOption("s", "d", false).integerOption("i", "d", false)
                .booleanOption("b", "d", false).userOption("u", "d", false).channelOption("c", "d", false)
                .roleOption("r", "d", false).numberOption("n", "d", false).attachmentOption("a", "d", false)
                .stringOption("absent", "d", false));
        when(option("s").getAsString()).thenReturn("hello");
        when(option("i").getAsInt()).thenReturn(7);
        when(option("b").getAsBoolean()).thenReturn(true);
        User user = mock(User.class);
        when(option("u").getAsUser()).thenReturn(user);
        GuildChannelUnion channel = mock(GuildChannelUnion.class);
        when(option("c").getAsChannel()).thenReturn(channel);
        Role role = mock(Role.class);
        when(option("r").getAsRole()).thenReturn(role);
        when(option("n").getAsDouble()).thenReturn(2.5);
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(option("a").getAsAttachment()).thenReturn(attachment);

        CommandContext context = parser.parse(event, command);

        assertEquals("hello", context.getOption("s").orElseThrow());
        assertEquals(7, context.getOption("i").orElseThrow());
        assertEquals(true, context.getOption("b").orElseThrow());
        assertSame(user, context.getOption("u").orElseThrow());
        assertSame(channel, context.getOption("c").orElseThrow());
        assertSame(role, context.getOption("r").orElseThrow());
        assertEquals(2.5, context.getOption("n").orElseThrow());
        assertSame(attachment, context.getOption("a").orElseThrow());
        assertTrue(context.getOption("absent").isEmpty(), "options Discord did not send are simply absent");
    }

    @Test
    void mentionableResolvesMemberThenRoleThenUserThenChannelOrFails() throws Exception {
        Command command = command(b -> b.mentionableOption("m", "d", true));
        OptionMapping mapping = option("m");

        Member member = mock(Member.class);
        when(mapping.getAsMember()).thenReturn(member);
        assertSame(member, parser.parse(event, command).getOption("m").orElseThrow());

        when(mapping.getAsMember()).thenReturn(null);
        Role role = mock(Role.class);
        when(mapping.getAsRole()).thenReturn(role);
        assertSame(role, parser.parse(event, command).getOption("m").orElseThrow());

        when(mapping.getAsRole()).thenReturn(null);
        User user = mock(User.class);
        when(mapping.getAsUser()).thenReturn(user);
        assertSame(user, parser.parse(event, command).getOption("m").orElseThrow());

        when(mapping.getAsUser()).thenReturn(null);
        GuildChannelUnion channel = mock(GuildChannelUnion.class);
        when(mapping.getAsChannel()).thenReturn(channel);
        assertSame(channel, parser.parse(event, command).getOption("m").orElseThrow());

        when(mapping.getAsChannel()).thenReturn(null);
        when(mapping.getAsString()).thenReturn("<@123>");
        CommandParseException e = assertThrows(CommandParseException.class, () -> parser.parse(event, command));
        assertTrue(e.getMessage().contains("<@123>"), e.getMessage());
    }

    @Test
    void aMappingThatCannotBeReadIsAParseError() {
        Command command = command(b -> b.integerOption("i", "d", true));
        when(option("i").getAsInt()).thenThrow(new ArithmeticException("integer overflow"));

        CommandParseException e = assertThrows(CommandParseException.class, () -> parser.parse(event, command));
        assertTrue(e.getMessage().contains("integer overflow"), e.getMessage());
    }

    @Test
    void aMissingRequiredOptionIsAParseError() {
        Command command = command(b -> b.stringOption("s", "d", true));
        assertThrows(CommandParseException.class, () -> parser.parse(event, command));
    }

    @Test
    void theSubcommandDiscordSelectedIsTheOneParsed() throws Exception {
        Command parent = new SimpleCommandBuilder().name("cmd").description("d")
                .subcommand(s -> s.name("one").description("d").stringOption("x", "d", true)
                        .executor((c, cmd) -> CommandResult.success()))
                .subcommand(s -> s.name("two").description("d").executor((c, cmd) -> CommandResult.success()))
                .build();
        when(event.getSubcommandName()).thenReturn("one");
        when(option("x").getAsString()).thenReturn("v");

        CommandContext context = parser.parse(event, parent);

        assertEquals("one", context.getCommand().getName());
        assertEquals("v", context.getOption("x").orElseThrow());
    }
}
