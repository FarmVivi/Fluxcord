package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The context handed to command executors: typed option access, validation, reply bookkeeping. */
class SimpleCommandContextTest {

    private final LanguageManager lang = mock(LanguageManager.class);
    private final JDA jda = mock(JDA.class);
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final Command command = new SimpleCommandBuilder().name("cmd").description("d")
            .stringOption("name", "n", true).integerOption("count", "c", false, 1, 10)
            .executor((c, cmd) -> CommandResult.success()).build();

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        when(lang.getString(any(), anyString())).thenAnswer(inv -> inv.getArgument(1, String.class));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private SimpleCommandContext context(Map<String, Object> options, User user, Guild guild) {
        return new SimpleCommandContext(new ConsoleCommandEvent(jda, "cmd"), command, user, guild,
                user == null ? null : mock(MessageChannel.class), Locale.US, options, lang);
    }

    @Test
    void optionsAreTypedWithDefaultsAndRequiredAccess() {
        SimpleCommandContext ctx = context(Map.of("name", "bob", "count", 3), null, null);

        assertEquals("bob", ctx.<String>getOption("name").orElseThrow());
        assertEquals(3, (Integer) ctx.getOption("count", 0));
        assertEquals(7, (Integer) ctx.getOption("missing", 7));
        assertTrue(ctx.<String>getOption("missing").isEmpty());
        assertEquals("bob", ctx.getRequiredOption("name"));
        assertThrows(IllegalArgumentException.class, () -> ctx.getRequiredOption("missing"));
        assertTrue(ctx.hasOption("name"));
        assertFalse(ctx.hasOption("missing"));

        assertEquals("count", ctx.<Integer>getOptionDefinition("count").map(CommandOption::getName).orElseThrow());
        assertTrue(ctx.getOptionDefinition("nope").isEmpty());
        assertSame(command, ctx.getCommand());
        assertSame(jda, ctx.getJDA());
        assertEquals(Locale.US, ctx.getLocale());
    }

    @Test
    void validationChecksPresenceAndValues() {
        assertDoesNotThrow(() -> context(Map.of("name", "bob", "count", 5), null, null).validateOptions());
        assertThrows(CommandParseException.class, () -> context(Map.of("count", 5), null, null).validateOptions(), "required missing");
        assertThrows(CommandParseException.class, () -> context(Map.of("name", "bob", "count", 50), null, null).validateOptions(), "out of range");
        assertThrows(CommandParseException.class, () -> context(Map.of("name", "bob", "count", "five"), null, null).validateOptions(), "wrong type");

        Map<String, Object> mutable = new HashMap<>();
        SimpleCommandContext ctx = context(mutable, null, null);
        ctx.addOption("name", "late");
        assertEquals("late", ctx.getRequiredOption("name"));
    }

    @Test
    void guildAndUserPresence() {
        User user = mock(User.class);
        Guild guild = mock(Guild.class);
        SimpleCommandContext inGuild = context(Map.of(), user, guild);
        assertTrue(inGuild.isFromGuild());
        assertSame(guild, inGuild.getGuild().orElseThrow());
        assertSame(user, inGuild.getUser());
        assertNotNull(inGuild.getChannel());

        SimpleCommandContext console = context(Map.of(), null, null);
        assertFalse(console.isFromGuild());
        assertNull(console.getUser());
    }

    @Test
    void repliesAreTrackedAndDeferralIsRememberedForInteractions() {
        SimpleCommandContext ctx = context(Map.of(), null, null);
        assertFalse(ctx.hasReplied());
        assertFalse(ctx.isDeferred());
        assertFalse(ctx.isEphemeral());

        ctx.setEphemeral(true);
        ctx.deferReply(); // console: no interaction to acknowledge, only the flags move
        assertTrue(ctx.isDeferred());
        assertTrue(ctx.isEphemeral());
        assertFalse(ctx.hasReplied(), "deferring is not replying");

        ctx.replySuccess("done");
        assertTrue(ctx.hasReplied());
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("[CONSOLE] done"));

        ctx.replyWarning("careful");
        ctx.replyInfo("fyi");
        ctx.reply("plain");
        String out = console.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("careful") && out.contains("fyi") && out.contains("[CONSOLE] plain"), out);
    }
}
