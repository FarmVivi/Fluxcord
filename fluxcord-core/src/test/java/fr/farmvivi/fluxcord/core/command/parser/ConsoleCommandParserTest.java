package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Console lines: any non-empty input is a command attempt, values are typed like text commands, users by id. */
class ConsoleCommandParserTest {

    private final LanguageManager lang = mock(LanguageManager.class);
    private final ConsoleCommandParser parser = new ConsoleCommandParser(lang);
    private final JDA jda = mock(JDA.class);

    @BeforeEach
    void setUp() {
        when(lang.getDefaultLocale()).thenReturn(Locale.FRANCE);
    }

    private static Command command(Consumer<CommandBuilder> options) {
        CommandBuilder builder = new SimpleCommandBuilder().name("cmd").aliases("c").description("d");
        options.accept(builder);
        return builder.executor((c, cmd) -> CommandResult.success()).build();
    }

    private ConsoleCommandEvent line(String input) {
        return new ConsoleCommandEvent(jda, input);
    }

    @Test
    void anyNonBlankLineIsAnInvocation() throws Exception {
        assertTrue(parser.canParse(line("x")));
        assertFalse(parser.canParse(mock(net.dv8tion.jda.api.events.Event.class)));
        assertTrue(parser.isCommandInvocation(line("cmd a")));
        assertFalse(parser.isCommandInvocation(line("   ")));
        assertEquals("cmd", parser.extractCommandName(line("CMD a b")));
        assertThrows(CommandParseException.class, () -> parser.extractCommandName(mock(net.dv8tion.jda.api.events.Event.class)));
    }

    @Test
    void contextHasNoUserNoGuildAndTheDefaultLocale() throws Exception {
        Command cmd = command(b -> b.stringOption("s", "s", false));
        CommandContext ctx = parser.parse(line("c hello"), cmd);
        assertNull(ctx.getUser());
        assertTrue(ctx.getGuild().isEmpty());
        assertFalse(ctx.isFromGuild());
        assertEquals(Locale.FRANCE, ctx.getLocale());
        assertEquals("hello", ctx.getRequiredOption("s"));
        assertSame(jda, ctx.getJDA());
        assertThrows(CommandParseException.class, () -> parser.parse(line("other"), cmd), "name mismatch");
        assertThrows(CommandParseException.class, () -> parser.parse(mock(net.dv8tion.jda.api.events.Event.class), cmd));
    }

    @Test
    void valuesAreTypedAndATrailingStringTakesTheRest() throws Exception {
        Command cmd = command(b -> b.integerOption("n", "n", true).booleanOption("b", "b", true)
                .numberOption("x", "x", false).stringOption("rest", "r", false));
        CommandContext ctx = parser.parse(line("cmd 3 yes 2.5 all the rest"), cmd);
        assertEquals(3, (Integer) ctx.getRequiredOption("n"));
        assertEquals(Boolean.TRUE, ctx.getRequiredOption("b"));
        assertEquals(2.5, (Double) ctx.getRequiredOption("x"));
        assertEquals("all the rest", ctx.getRequiredOption("rest"));

        assertThrows(CommandParseException.class, () -> parser.parse(line("cmd three yes"), cmd), "required int");
        assertThrows(CommandParseException.class, () -> parser.parse(line("cmd 3 maybe"), cmd), "required boolean");
        assertThrows(CommandParseException.class, () -> parser.parse(line("cmd 3"), cmd), "missing required");
    }

    @Test
    void usersResolveByIdThroughTheCacheThenTheRest() throws Exception {
        Command cmd = command(b -> b.userOption("who", "w", true));
        User cached = mock(User.class);
        when(jda.getUserById("123456789012345678")).thenReturn(cached);
        assertSame(cached, parser.parse(line("cmd 123456789012345678"), cmd).getRequiredOption("who"));
        assertSame(cached, parser.parse(line("cmd <@123456789012345678>"), cmd).getRequiredOption("who"), "mentions are stripped to the id");

        User fetched = mock(User.class);
        @SuppressWarnings("unchecked")
        CacheRestAction<User> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(fetched);
        when(jda.retrieveUserById("999999999999999999")).thenReturn(action);
        assertSame(fetched, parser.parse(line("cmd 999999999999999999"), cmd).getRequiredOption("who"));

        assertThrows(CommandParseException.class, () -> parser.parse(line("cmd bob"), cmd), "no digits");
    }

    @Test
    void discordOnlyTypesAreRefusedOnTheConsole() {
        Command channel = command(b -> b.channelOption("ch", "c", true));
        Command optional = command(b -> b.roleOption("r", "r", false).stringOption("s", "s", false));
        assertThrows(CommandParseException.class, () -> parser.parse(line("cmd general"), channel));
        assertDoesNotThrow(() -> {
            CommandContext ctx = parser.parse(line("cmd admins"), optional);
            assertFalse(ctx.hasOption("r"), "optional unsupported option is skipped");
            assertEquals("admins", ctx.getRequiredOption("s"), "and its token goes to the next option");
        });
    }

    @Test
    void splittingHandlesQuotesAndEmptyInput() {
        assertEquals(List.of("a", "b c", "d"), PositionalArguments.split("a \"b c\"   d"));
        assertEquals(List.of("it's", "x"), PositionalArguments.split("\"it's\" x"), "the other quote is literal inside");
        assertEquals(List.of(), PositionalArguments.split("   "));
        assertEquals(List.of("pre", "quoted"), PositionalArguments.split("pre\"quoted\""), "a quote starts a new token");
    }

    @Test
    void assignmentContractIsSharedByTheParsers() throws Exception {
        List<CommandOption<?>> options = command(b -> b.integerOption("n", "n", false).stringOption("s", "s", true)).getOptions();
        Map<String, Object> values = PositionalArguments.assign("x y z", options, (token, option) -> {
            if (option.getName().equals("n")) throw new CommandParseException("not a number");
            return token;
        });
        assertEquals(Map.of("s", "x y z"), values, "the optional int was omitted: its token goes to the next option");
        assertTrue(PositionalArguments.assign("", options, (t, o) -> t).isEmpty());
    }
}
