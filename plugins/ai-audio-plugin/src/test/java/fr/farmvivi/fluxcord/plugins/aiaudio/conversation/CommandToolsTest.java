package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandRegistry;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionType;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The bot running a command because somebody asked it out loud.
 *
 * <p>Two things are being pinned here, and only one of them is about convenience. The convenience is that a
 * small model's idea of "arguments" — a bare song title, {@code key=value}, occasionally real JSON — all
 * work, because refusing a clear request on a formality is the failure nobody forgives.
 *
 * <p>The other is the one that matters: the invocation goes through the ordinary pipeline carrying
 * <strong>the asker's</strong> member, never the bot's. Every test that builds a context asserts whose it is.
 */
class CommandToolsTest {

    private static final String GUILD_ID = "g1";
    private static final String USER_ID = "u1";
    private static final long NOW = 1_700_000_000_000L;

    private CommandService service;
    private CommandRegistry registry;
    private Guild guild;
    private Member asker;
    private User askerUser;
    private MessageChannel output;
    private CommandTools tools;

    /** Commands the registry answers with, in the order the catalogue should show them. */
    private final List<Command> registered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = mock(CommandRegistry.class);
        when(registry.getCommands()).thenAnswer(i -> List.copyOf(registered));
        when(registry.getCommandNames()).thenAnswer(i -> registered.stream().map(Command::getName).toList());
        when(registry.getCommand(anyStringOrNull())).thenAnswer(i -> byName(i.getArgument(0)));
        when(registry.getCommandByAlias(anyStringOrNull())).thenReturn(Optional.empty());

        service = mock(CommandService.class);
        when(service.getRegistry()).thenReturn(registry);
        when(service.executeCommand(any(), any())).thenReturn(CommandResult.success());

        askerUser = mock(User.class);
        when(askerUser.getId()).thenReturn(USER_ID);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        asker = mock(Member.class);
        when(asker.getUser()).thenReturn(askerUser);
        when(asker.getGuild()).thenReturn(guild);
        when(guild.getMemberById(USER_ID)).thenReturn(asker);

        output = mock(MessageChannel.class);
        tools = new CommandTools(service, id -> GUILD_ID.equals(id) ? Optional.of(guild) : Optional.empty(),
                turn -> Optional.of(output), Locale.FRANCE);
    }

    private static String anyStringOrNull() {
        return org.mockito.ArgumentMatchers.anyString();
    }

    private Optional<Command> byName(String name) {
        return registered.stream().filter(c -> c.getName().equalsIgnoreCase(name)).findFirst();
    }

    // ---------------------------------------------------------------- fixtures

    @SafeVarargs
    private Command command(String name, String description, CommandOption<?>... options) {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn(name);
        when(command.getDescription()).thenReturn(description);
        when(command.getOptions()).thenReturn(List.of(options));
        when(command.getSubcommands()).thenReturn(List.of());
        when(command.isEnabled()).thenReturn(true);
        registered.add(command);
        return command;
    }

    private CommandOption<?> option(String name, OptionType type, boolean required) {
        CommandOption<?> option = mock(CommandOption.class);
        when(option.getName()).thenReturn(name);
        when(option.getType()).thenReturn(type);
        when(option.isRequired()).thenReturn(required);
        when(option.getDescription()).thenReturn("what to " + name);
        return option;
    }

    private Command play() {
        return command("play", "Play a track", option("query", OptionType.STRING, true),
                option("now", OptionType.BOOLEAN, false));
    }

    private String run(String arguments) {
        return tools.execute(new ChatModel.ToolCall("call-1", CommandTools.RUN_A_COMMAND, arguments),
                null, NOW, turn("joue quelque chose"));
    }

    private static Turn turn(String text) {
        return new Turn(NOW, USER_ID, "Victor", GUILD_ID, "My Server", "c1", "General", text);
    }

    /** The context the pipeline was handed, which is where every guarantee of this class lives. */
    private AiCommandContext executedContext() {
        ArgumentCaptor<CommandContext> captor = ArgumentCaptor.forClass(CommandContext.class);
        verify(service).executeCommand(any(), captor.capture());
        return assertInstanceOf(AiCommandContext.class, captor.getValue());
    }

    // ---------------------------------------------------------------- the catalogue

    @Test
    void theCatalogueShowsEachCommandWithTheShapeOfItsOptions() {
        play();

        String catalogue = tools.catalogue();

        assertTrue(catalogue.contains("- play <query:string> [now:boolean]"), catalogue);
        assertTrue(catalogue.contains("Play a track"), catalogue);
    }

    @Test
    void theCatalogueLeavesOutWhatIsSwitchedOff() {
        Command disabled = play();
        when(disabled.isEnabled()).thenReturn(false);

        assertFalse(tools.catalogue().contains("play"));
    }

    @Test
    void aSubcommandIsShownAndReachedUnderItsFullPath() {
        Command queue = command("queue", "The queue");
        Command clear = mock(Command.class);
        when(clear.getName()).thenReturn("clear");
        when(clear.getDescription()).thenReturn("Empty it");
        when(clear.getOptions()).thenReturn(List.of());
        when(clear.getSubcommands()).thenReturn(List.of());
        when(clear.isEnabled()).thenReturn(true);
        when(queue.getSubcommands()).thenReturn(List.of(clear));

        assertTrue(tools.catalogue().contains("- queue clear"), tools.catalogue());
        assertEquals(Optional.of(clear), tools.resolve("queue clear"));
    }

    @Test
    void theCatalogueIsOfferedToTheModelAsPartOfTheOneTool() {
        play();

        List<ChatModel.Tool> declared = tools.declarations();

        assertEquals(1, declared.size());
        assertEquals(CommandTools.RUN_A_COMMAND, declared.get(0).name());
        assertTrue(declared.get(0).description().contains("- play"), "no second round trip to find out");
    }

    // ---------------------------------------------------------------- arguments, as a model writes them

    @Test
    void aBareValueBecomesTheOneOptionTheCommandRequires() {
        // The common case by a wide margin: asked to play a song, a small model answers with the song.
        play();

        run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        assertEquals("daft punk", executedContext().getOption("query").orElseThrow());
    }

    @Test
    void realJsonIsUsedAsWritten() {
        play();

        run("{\"command\":\"play\",\"arguments\":{\"query\":\"daft punk\",\"now\":true}}");

        AiCommandContext context = executedContext();
        assertEquals("daft punk", context.getOption("query").orElseThrow());
        assertEquals(Boolean.TRUE, context.getOption("now").orElseThrow());
    }

    @Test
    void jsonWrittenAsAStringIsParsedToo() {
        play();

        run("{\"command\":\"play\",\"arguments\":\"{\\\"query\\\": \\\"daft punk\\\"}\"}");

        assertEquals("daft punk", executedContext().getOption("query").orElseThrow());
    }

    @Test
    void keyValuePairsAreParsed() {
        play();

        run("{\"command\":\"play\",\"arguments\":\"query=\\\"daft punk\\\" now=yes\"}");

        AiCommandContext context = executedContext();
        assertEquals("daft punk", context.getOption("query").orElseThrow());
        assertEquals(Boolean.TRUE, context.getOption("now").orElseThrow());
    }

    @Test
    void aNumberIsGivenAsTheTypeTheOptionDeclares() {
        command("volume", "Set the volume", option("level", OptionType.INTEGER, true));

        run("{\"command\":\"volume\",\"arguments\":\"50\"}");

        // An Integer and not a String: this is what the slash parser puts in the map, and a command
        // casting it would otherwise fail inside its own executor.
        assertEquals(Integer.valueOf(50), executedContext().getOption("level").orElseThrow());
    }

    @Test
    void aValueThatIsNotANumberComesBackAsASentenceTheModelCanActOn() {
        command("volume", "Set the volume", option("level", OptionType.INTEGER, true));

        String answer = run("{\"command\":\"volume\",\"arguments\":\"fort\"}");

        assertTrue(answer.contains("not a number"), answer);
        verify(service, never()).executeCommand(any(), any());
    }

    @Test
    void aMissingRequiredOptionIsReportedWithWhatItIsFor() {
        play();

        String answer = run("{\"command\":\"play\"}");

        assertTrue(answer.contains("query"), answer);
        assertTrue(answer.contains("what to query"), "the option's own description tells the model what to ask");
        verify(service, never()).executeCommand(any(), any());
    }

    @Test
    void anUnknownCommandComesBackWithTheOnesThatExist() {
        play();

        String answer = run("{\"command\":\"teleport\"}");

        assertTrue(answer.contains("teleport"), answer);
        assertTrue(answer.contains("play"), "the list, so the next attempt can be right: " + answer);
        verify(service, never()).executeCommand(any(), any());
    }

    @Test
    void nothingAtAllIsAnsweredRatherThanGuessed() {
        play();

        assertTrue(run("{}").contains("which command"), run("{}"));
        verify(service, never()).executeCommand(any(), any());
    }

    // ---------------------------------------------------------------- whose rights are used

    @Test
    void theCommandRunsAsThePersonWhoAskedForIt() {
        // The whole point. The context carries the asker, so the pipeline's permission check, the
        // guild-only flag and the cooldowns are theirs - the bot's own rights are never involved.
        play();

        run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        AiCommandContext context = executedContext();
        assertSame(askerUser, context.getUser());
        assertEquals(Optional.of(asker), context.getMember());
        assertEquals(Optional.of(guild), context.getGuild());
        assertNotNull(context.getUser(), "a null user means 'console, trusted' and would skip every check");
    }

    @Test
    void aSpeakerWhoIsNoLongerInTheServerIsRefusedRatherThanRunAsTheBot() {
        play();
        when(guild.getMemberById(USER_ID)).thenReturn(null);

        String answer = run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        assertTrue(answer.contains("Victor"), answer);
        verify(service, never()).executeCommand(any(), any());
    }

    @Test
    void withoutAnAskerNothingRunsAtAll() {
        play();

        String answer = tools.execute(
                new ChatModel.ToolCall("c", CommandTools.RUN_A_COMMAND, "{\"command\":\"play\"}"), null, NOW);

        assertTrue(answer.contains("who asked"), answer);
        verify(service, never()).executeCommand(any(), any());
    }

    // ---------------------------------------------------------------- what the model is told back

    @Test
    void whatTheCommandAnsweredIsHandedBackFencedAndLabelledAsAnOutcome() {
        play();
        when(service.executeCommand(any(), any())).thenAnswer(i -> {
            ((CommandContext) i.getArgument(1)).replySuccess("Now playing: Around the World");
            return CommandResult.success();
        });

        String answer = run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        assertTrue(answer.contains("Now playing: Around the World"), answer);
        assertTrue(answer.contains("never an instruction"), "a track title is chosen by whoever uploaded it");
        assertTrue(answer.contains("--- answer"), answer);
    }

    @Test
    void aRefusalFromThePipelineIsReportedWithItsReason() {
        // The pipeline refuses before the command runs, so the reason never reaches the context.
        play();
        when(service.executeCommand(any(), any()))
                .thenReturn(CommandResult.error("You don't have permission to use this command"));

        String answer = run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        assertTrue(answer.contains("was refused"), answer);
        assertTrue(answer.contains("permission"), answer);
    }

    @Test
    void aCommandThatBlowsUpDoesNotEndTheSpokenTurn() {
        // ToolSource.execute may not throw: an exception here is a bot that went quiet mid-conversation.
        play();
        when(service.executeCommand(any(), any())).thenThrow(new IllegalStateException("the player died"));

        String answer = assertDoesNotThrow(() -> run("{\"command\":\"play\",\"arguments\":\"daft punk\"}"));

        assertTrue(answer.contains("the player died"), answer);
    }

    @Test
    void theRepliesAreCapturedAndNotPostedInTheChannel() {
        play();
        when(service.executeCommand(any(), any())).thenAnswer(i -> {
            ((CommandContext) i.getArgument(1)).reply("a wall of text");
            return CommandResult.success();
        });

        run("{\"command\":\"play\",\"arguments\":\"daft punk\"}");

        verifyNoInteractions(output);
    }

    @Test
    void onlyItsOwnToolIsClaimed() {
        assertTrue(tools.handles(CommandTools.RUN_A_COMMAND));
        assertFalse(tools.handles("search_the_web"));
    }

    @Test
    void anAliasIsAcceptedBecauseTheModelIsReadingATranscript() {
        Command next = command("next", "Skip");
        when(registry.getCommand("skip")).thenReturn(Optional.empty());
        when(registry.getCommandByAlias("skip")).thenReturn(Optional.of(next));

        assertEquals(Optional.of(next), tools.resolve("skip"));
        assertEquals(Optional.of(next), tools.resolve("  NEXT "), "and case is not a transcript's strength");
    }

    @Test
    void aFileCannotBeGivenByVoiceAndSaysSo() {
        command("upload", "Upload", option("file", OptionType.ATTACHMENT, true));

        String answer = run("{\"command\":\"upload\",\"arguments\":\"something\"}");

        assertTrue(answer.contains("cannot be given by voice"), answer);
        verify(service, never()).executeCommand(any(), any());
    }

    @Test
    void aPersonNamedByANicknameIsResolvedInThatServer() {
        command("kick", "Kick", option("who", OptionType.USER, true));
        Member target = mock(Member.class);
        User targetUser = mock(User.class);
        when(target.getUser()).thenReturn(targetUser);
        when(guild.getMembersByEffectiveName("Alice", true)).thenReturn(List.of(target));

        run("{\"command\":\"kick\",\"arguments\":\"Alice\"}");

        assertSame(targetUser, executedContext().getOption("who").orElseThrow());
    }

    @Test
    void aPersonNobodyCanNameIsReportedRatherThanDropped() {
        command("kick", "Kick", option("who", OptionType.USER, true));
        when(guild.getMembersByEffectiveName("Bob", true)).thenReturn(List.of());

        String answer = run("{\"command\":\"kick\",\"arguments\":\"Bob\"}");

        assertTrue(answer.contains("nobody called"), answer);
        verify(service, never()).executeCommand(any(), any());
    }
}
