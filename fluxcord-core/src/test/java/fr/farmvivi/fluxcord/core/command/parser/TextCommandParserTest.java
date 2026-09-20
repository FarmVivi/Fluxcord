package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prefixed text commands: prefix detection, argument splitting and the per-type value resolution against JDA. */
class TextCommandParserTest {

    private final LanguageManager lang = mock(LanguageManager.class);
    private final CommandService commandService = mock(CommandService.class);
    private final TextCommandParser parser = new TextCommandParser(lang, commandService);
    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);

    @BeforeEach
    void setUp() {
        when(lang.getDefaultLocale()).thenReturn(Locale.US);
        when(commandService.getPrefix(null)).thenReturn("!");
        when(commandService.getPrefix("g1")).thenReturn("?");
        when(guild.getId()).thenReturn("g1");
        when(guild.getMembersByName(anyString(), anyBoolean())).thenReturn(List.of());
        when(guild.getMembersByEffectiveName(anyString(), anyBoolean())).thenReturn(List.of());
        when(guild.getRolesByName(anyString(), anyBoolean())).thenReturn(List.of());
        when(guild.getChannels()).thenReturn(List.of());
    }

    private static Command command(Consumer<CommandBuilder> options) {
        CommandBuilder builder = new SimpleCommandBuilder().name("cmd").aliases("c").description("d");
        options.accept(builder);
        return builder.executor((c, cmd) -> CommandResult.success()).build();
    }

    private MessageReceivedEvent message(String content, boolean inGuild) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        Message message = mock(Message.class);
        User author = mock(User.class);
        when(event.getMessage()).thenReturn(message);
        when(event.getAuthor()).thenReturn(author);
        when(event.getJDA()).thenReturn(jda);
        when(event.isFromGuild()).thenReturn(inGuild);
        when(event.getGuild()).thenReturn(inGuild ? guild : null);
        when(event.isFromType(ChannelType.TEXT)).thenReturn(inGuild);
        when(event.getChannel()).thenReturn(mock(MessageChannelUnion.class));
        when(message.getContentRaw()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        when(author.isBot()).thenReturn(false);
        return event;
    }

    // ---- detection -------------------------------------------------------------------------------------------

    @Test
    void invocationNeedsThePrefixOfTheGuildAndAHumanAuthor() throws Exception {
        assertTrue(parser.canParse(message("!cmd", false)));
        assertTrue(parser.isCommandInvocation(message("!cmd", false)));
        assertFalse(parser.isCommandInvocation(message("?cmd", false)), "DM prefix is the default one");
        assertTrue(parser.isCommandInvocation(message("?cmd", true)), "guild prefix");
        assertFalse(parser.isCommandInvocation(message("!cmd", true)));

        MessageReceivedEvent bot = message("!cmd", false);
        when(bot.getAuthor().isBot()).thenReturn(true);
        assertFalse(parser.isCommandInvocation(bot));

        assertEquals("cmd", parser.extractCommandName(message("!CMD a b", false)));
        assertThrows(CommandParseException.class, () -> parser.extractCommandName(message("hello", false)));
        assertThrows(CommandParseException.class, () -> parser.extractCommandName(mock(net.dv8tion.jda.api.events.Event.class)));
    }

    @Test
    void parseChecksTheNameAndAliasAndReadsTheDefaultLocale() throws Exception {
        Command cmd = command(b -> { });
        CommandContext context = parser.parse(message("!c", false), cmd);
        assertEquals(Locale.US, context.getLocale());
        assertNull(context.getGuild().orElse(null));
        assertThrows(CommandParseException.class, () -> parser.parse(message("!other", false), cmd));
        assertThrows(CommandParseException.class, () -> parser.parse(message("nope", false), cmd));
    }

    // ---- splitting and simple types ----------------------------------------------------------------------------

    @Test
    void argumentsAreSplitOnWhitespaceWithQuotes() throws Exception {
        Command cmd = command(b -> b.stringOption("a", "a", true).stringOption("b", "b", true).integerOption("n", "n", false));
        CommandContext ctx = parser.parse(message("!cmd  \"hello world\" 'single quoted'   7", false), cmd);
        assertEquals("hello world", ctx.getRequiredOption("a"));
        assertEquals("single quoted", ctx.getRequiredOption("b"));
        assertEquals(7, (Integer) ctx.getRequiredOption("n"));
    }

    @Test
    void aTrailingStringOptionTakesTheRestOfTheLine() throws Exception {
        Command play = command(b -> b.stringOption("query", "q", true));
        assertEquals("never gonna give you up",
                parser.parse(message("!cmd never gonna give you up", false), play).getRequiredOption("query"));

        Command two = command(b -> b.integerOption("n", "n", true).stringOption("text", "t", true));
        CommandContext ctx = parser.parse(message("!cmd 3 the rest of it", false), two);
        assertEquals(3, (Integer) ctx.getRequiredOption("n"));
        assertEquals("the rest of it", ctx.getRequiredOption("text"));

        Command notLast = command(b -> b.stringOption("first", "f", true).integerOption("n", "n", false));
        CommandContext c2 = parser.parse(message("!cmd one 2", false), notLast);
        assertEquals("one", c2.getRequiredOption("first"), "only the last option absorbs");
        assertEquals(2, (Integer) c2.getRequiredOption("n"));
    }

    @Test
    void booleansNumbersAndFailures() throws Exception {
        Command cmd = command(b -> b.booleanOption("flag", "f", true).numberOption("x", "x", false));
        for (String yes : List.of("true", "YES", "y", "1")) {
            assertEquals(Boolean.TRUE, parser.parse(message("!cmd " + yes, false), cmd).getRequiredOption("flag"), yes);
        }
        for (String no : List.of("false", "no", "N", "0")) {
            assertEquals(Boolean.FALSE, parser.parse(message("!cmd " + no, false), cmd).getRequiredOption("flag"), no);
        }
        assertEquals(1.5, (Double) parser.parse(message("!cmd yes 1.5", false), cmd).getRequiredOption("x"));

        CommandParseException e = assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd maybe", false), cmd));
        assertTrue(e.getMessage().contains("flag"), e.getMessage());
        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd", false), cmd), "required option missing");

        Command optional = command(b -> b.integerOption("n", "n", false).stringOption("s", "s", false));
        CommandContext ctx = parser.parse(message("!cmd notanumber", false), optional);
        assertFalse(ctx.hasOption("n"), "an optional value that does not parse is skipped");
    }

    // ---- Discord entities -------------------------------------------------------------------------------------

    @Test
    void usersResolveByMentionIdOrName() throws Exception {
        Command cmd = command(b -> b.userOption("who", "w", true));
        User user = mock(User.class);
        when(jda.getUserById("42")).thenReturn(user);
        when(jda.getUserById(42L)).thenReturn(user);
        assertSame(user, parser.parse(message("!cmd <@42>", false), cmd).getRequiredOption("who"));
        assertSame(user, parser.parse(message("!cmd <@!42>", false), cmd).getRequiredOption("who"));
        assertSame(user, parser.parse(message("!cmd 42", false), cmd).getRequiredOption("who"));

        Member member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(guild.getMembersByEffectiveName("Bob", true)).thenReturn(List.of(member));
        assertSame(user, parser.parse(message("?cmd Bob", true), cmd).getRequiredOption("who"), "by (effective) name in a guild");

        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd nobody", false), cmd));
    }

    @Test
    void channelsAndRolesNeedAGuild() throws Exception {
        Command channels = command(b -> b.channelOption("ch", "c", true));
        Command roles = command(b -> b.roleOption("r", "r", true));
        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd <#1>", false), channels));
        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd <@&1>", false), roles));

        TextChannel channel = mock(TextChannel.class);
        when(channel.getName()).thenReturn("general");
        when(guild.getGuildChannelById("7")).thenReturn(channel);
        when(guild.getGuildChannelById(7L)).thenReturn(channel);
        doReturn(List.of(channel)).when(guild).getChannels();
        assertSame(channel, parser.parse(message("?cmd <#7>", true), channels).getRequiredOption("ch"));
        assertSame(channel, parser.parse(message("?cmd 7", true), channels).getRequiredOption("ch"));
        assertSame(channel, parser.parse(message("?cmd GENERAL", true), channels).getRequiredOption("ch"));
        assertThrows(CommandParseException.class, () -> parser.parse(message("?cmd nope", true), channels));

        Role role = mock(Role.class);
        when(guild.getRoleById("9")).thenReturn(role);
        when(guild.getRoleById(9L)).thenReturn(role);
        when(guild.getRolesByName("Admins", true)).thenReturn(List.of(role));
        assertSame(role, parser.parse(message("?cmd <@&9>", true), roles).getRequiredOption("r"));
        assertSame(role, parser.parse(message("?cmd 9", true), roles).getRequiredOption("r"));
        assertSame(role, parser.parse(message("?cmd Admins", true), roles).getRequiredOption("r"));
        assertThrows(CommandParseException.class, () -> parser.parse(message("?cmd nope", true), roles));
    }

    @Test
    void mentionableTriesUserThenRoleThenChannel() throws Exception {
        Command cmd = command(b -> b.mentionableOption("m", "m", true));
        Role role = mock(Role.class);
        when(guild.getRoleById("9")).thenReturn(role);
        assertSame(role, parser.parse(message("?cmd <@&9>", true), cmd).getRequiredOption("m"));

        GuildChannel channel = mock(GuildChannel.class);
        when(guild.getGuildChannelById("7")).thenReturn(channel);
        assertSame(channel, parser.parse(message("?cmd <#7>", true), cmd).getRequiredOption("m"));

        assertThrows(CommandParseException.class, () -> parser.parse(message("?cmd nothing", true), cmd));
        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd <@&9>", false), cmd), "no roles outside guilds");
    }

    @Test
    void attachmentOptionTakesTheFirstAttachment() throws Exception {
        Command cmd = command(b -> b.attachmentOption("file", "f", true));
        MessageReceivedEvent event = message("!cmd x", false);
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(event.getMessage().getAttachments()).thenReturn(List.of(attachment));
        assertSame(attachment, parser.parse(event, cmd).getRequiredOption("file"));

        assertThrows(CommandParseException.class, () -> parser.parse(message("!cmd x", false), cmd), "no attachment");
    }
}
