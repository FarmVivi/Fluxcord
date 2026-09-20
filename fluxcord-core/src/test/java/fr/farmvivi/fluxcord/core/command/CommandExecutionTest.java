package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.event.CommandExecuteEvent;
import fr.farmvivi.fluxcord.api.command.event.CommandExecutedEvent;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Characterization of the execution pipeline (plan items C1/C2): gating order, console exemptions, cooldowns,
 * events, metrics, and what the user gets back when a command is refused or cannot be parsed.
 */
class CommandExecutionTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final LanguageManager lang = mock(LanguageManager.class);
    private final PermissionManager permissions = mock(PermissionManager.class);
    private final JDA jda = mock(JDA.class);
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        // i18n: answer with the key and the arguments, so assertions read like the message catalogue
        when(lang.getString(any(), anyString(), any(Object[].class))).thenAnswer(inv -> {
            StringBuilder s = new StringBuilder(inv.getArgument(1, String.class));
            Object[] args = inv.getArguments();
            for (int i = 2; i < args.length; i++) {
                s.append('|').append(args[i]);
            }
            return s.toString();
        });
        when(lang.getString(any(), anyString())).thenAnswer(inv -> inv.getArgument(1, String.class));
        when(lang.getDefaultLocale()).thenReturn(Locale.US);
        when(permissions.hasPermission(anyString(), any(), anyString())).thenReturn(true);
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS); // not CONNECTED: enable() skips the slash sync

        service = new SimpleCommandService(events, lang, permissions, commands("!"), mock(DataStorageManager.class));
        service.setJDA(jda);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        service.disable();
        events.shutdown();
    }

    private Command register(String name, java.util.function.Consumer<CommandBuilder> customize,
                             java.util.function.BiFunction<CommandContext, Command, CommandResult> executor) {
        CommandBuilder builder = new SimpleCommandBuilder().name(name).description("d");
        customize.accept(builder);
        Command command = builder.executor(executor::apply).build();
        service.getRegistry().register(command, null);
        return command;
    }

    private static Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getId()).thenReturn("test");
        when(plugin.getName()).thenReturn("test");
        return plugin;
    }

    private CommandContext consoleContext(Command command) {
        return new SimpleCommandContext(new ConsoleCommandEvent(jda, command.getName()), command,
                null, null, null, Locale.US, Map.of(), lang);
    }

    private CommandContext userContext(Command command, String userId, Guild guild) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        return new SimpleCommandContext(event, command, user, guild, mock(MessageChannelUnion.class), Locale.US, Map.of(), lang);
    }

    // ---- executeCommand gating -------------------------------------------------------------------------------

    @Test
    void disabledCommandIsRefusedBeforeAnythingElse() {
        Command command = register("x", b -> b.enabled(false), (c, cmd) -> fail("must not run"));
        CommandResult result = service.executeCommand(command, userContext(command, "u", null));
        assertEquals("commands.messages.disabled", result.getErrorMessage());
        assertEquals(0, service.getCommandExecutionCount(), "refusals are not counted as executions");
    }

    @Test
    void guildOnlyIsEnforcedForUsersNotForConsole() {
        AtomicInteger runs = new AtomicInteger();
        Command command = register("x", b -> b.guildOnly(true), (c, cmd) -> { runs.incrementAndGet(); return CommandResult.success(); });

        assertEquals("commands.messages.guild_only", service.executeCommand(command, userContext(command, "u", null)).getErrorMessage());
        assertTrue(service.executeCommand(command, consoleContext(command)).isSuccess());
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("g");
        assertTrue(service.executeCommand(command, userContext(command, "u", guild)).isSuccess());
        assertEquals(2, runs.get());
    }

    @Test
    void permissionIsCheckedAgainstTheGuildAndSkippedForConsole() {
        Command command = register("x", b -> b.permission("music.volume"), (c, cmd) -> CommandResult.success());
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("g");
        when(permissions.hasPermission("u", "g", "music.volume")).thenReturn(false);

        CommandResult denied = service.executeCommand(command, userContext(command, "u", guild));
        assertTrue(denied.getErrorMessage().startsWith("commands.messages.permission_error|"), denied.getErrorMessage());
        assertTrue(service.executeCommand(command, consoleContext(command)).isSuccess());
        verify(permissions, never()).hasPermission(eq("CONSOLE"), any(), anyString());
    }

    @Test
    void cooldownAppliesAfterASuccessfulRunAndOnlyToUsers() {
        Command command = register("x", b -> b.cooldown(60), (c, cmd) -> CommandResult.success());
        assertFalse(service.isOnCooldown("u", "x"));

        assertTrue(service.executeCommand(command, userContext(command, "u", null)).isSuccess());
        assertTrue(service.isOnCooldown("u", "x"));
        assertTrue(service.getRemainingCooldown("u", "x") > 50);
        assertTrue(service.executeCommand(command, userContext(command, "u", null)).getErrorMessage()
                .startsWith("commands.messages.cooldown|"));
        assertFalse(service.isOnCooldown("other", "x"), "cooldowns are per user");
        assertTrue(service.executeCommand(command, consoleContext(command)).isSuccess());
        assertTrue(service.executeCommand(command, consoleContext(command)).isSuccess(), "console never cools down");
    }

    @Test
    void cancellingTheExecuteEventRefusesTheCommandAndExecutedEventReportsTheResult() {
        List<CommandExecutedEvent> executed = new ArrayList<>();
        events.registerListener(new Object() {
            @EventHandler
            public void onExecute(CommandExecuteEvent e) { if (e.getCommand().getName().equals("blocked")) e.setCancelled(true); }
            @EventHandler
            public void onExecuted(CommandExecutedEvent e) { executed.add(e); }
        }, plugin());
        Command blocked = register("blocked", b -> { }, (c, cmd) -> fail("cancelled"));
        Command ok = register("ok", b -> { }, (c, cmd) -> CommandResult.success());

        assertEquals("commands.messages.execution_cancelled", service.executeCommand(blocked, consoleContext(blocked)).getErrorMessage());
        assertTrue(service.executeCommand(ok, consoleContext(ok)).isSuccess());
        assertEquals(1, executed.size());
        assertSame(ok, executed.get(0).getCommand());
        assertTrue(executed.get(0).getResult().isSuccess());
    }

    @Test
    void exceptionsBecomeErrorResultsAndMetricsCountBoth() {
        Command boom = register("boom", b -> { }, (c, cmd) -> { throw new IllegalStateException("kaboom"); });
        Command ok = register("ok", b -> { }, (c, cmd) -> CommandResult.success());

        assertEquals("commands.messages.execution_error|kaboom", service.executeCommand(boom, consoleContext(boom)).getErrorMessage());
        service.executeCommand(ok, consoleContext(ok));
        assertEquals(2, service.getCommandExecutionCount());
        assertEquals(1, service.getSuccessfulCommandExecutionCount());
        assertEquals(1, service.getFailedCommandExecutionCount());
        assertTrue(service.getAverageExecutionTimeMs() >= 0);
    }

    @Test
    void disabledServiceRefusesEverything() {
        Command ok = register("ok", b -> { }, (c, cmd) -> CommandResult.success());
        service.disable();
        assertEquals("commands.messages.system_disabled", service.executeCommand(ok, consoleContext(ok)).getErrorMessage());
    }

    // ---- processCommand: parsers and replies ------------------------------------------------------------------

    @Test
    void consoleInputIsParsedAndAliasesResolve() {
        AtomicInteger runs = new AtomicInteger();
        register("ping", b -> b.aliases("p").stringOption("who", "w", false),
                (c, cmd) -> { runs.incrementAndGet(); assertEquals("bob", c.getRequiredOption("who")); return CommandResult.success(); });

        service.processCommand(new ConsoleCommandEvent(jda, "p bob"));
        service.processCommand(new ConsoleCommandEvent(jda, "PING bob"));
        service.processCommand(new ConsoleCommandEvent(jda, "nope"));
        assertEquals(2, runs.get());
    }

    @Test
    void slashRefusalIsRepliedEvenWhenTheCommandNeverAcknowledgedTheInteraction() {
        register("volume", b -> b.permission("music.volume"), (c, cmd) -> fail("denied"));
        when(permissions.hasPermission(anyString(), any(), anyString())).thenReturn(false);
        SlashCommandInteractionEvent event = slashEvent("volume", false);

        service.processCommand(event);

        assertTrue(repliedEmbed(event).startsWith("commands.messages.permission_error|"));
    }

    @Test
    void slashParseFailureIsRepliedAsAUsageErrorInsteadOfBeingSwallowed() {
        register("play", b -> b.stringOption("query", "q", true), (c, cmd) -> fail("not parsed"));
        SlashCommandInteractionEvent event = slashEvent("play", false); // no option given → required option missing

        service.processCommand(event);

        assertTrue(repliedEmbed(event).startsWith("commands.messages.parse_error|"));
    }

    @Test
    void deferredSlashSuccessIsLeftToTheCommandAndDeferredFailureIsEdited() {
        register("ok", b -> { }, (c, cmd) -> CommandResult.success());
        register("ko", b -> { }, (c, cmd) -> CommandResult.error("nope"));
        SlashCommandInteractionEvent ok = slashEvent("ok", true);
        SlashCommandInteractionEvent ko = slashEvent("ko", true);

        service.processCommand(ok);
        service.processCommand(ko);

        verify(ok, never()).reply(anyString());
        verify(ok.getHook(), never()).editOriginal(anyString());
        verify(ko.getHook()).editOriginal(anyString()); // errors are embeds: content is empty, the embed carries "nope"
    }

    /** Errors are sent as an embed on {@code event.reply("").addEmbeds(...)}: returns that embed's description. */
    @SuppressWarnings("unchecked")
    private static String repliedEmbed(SlashCommandInteractionEvent event) {
        ReplyCallbackAction action = event.reply("");
        ArgumentCaptor<Collection<MessageEmbed>> embeds = ArgumentCaptor.forClass(Collection.class);
        verify(action).addEmbeds(embeds.capture());
        return embeds.getValue().iterator().next().getDescription();
    }

    @Test
    void aCommandThatAlreadyRepliedIsNotAnsweredASecondTime() {
        register("usage", b -> { }, (c, cmd) -> { c.replyError("Usage: usage <x>"); return CommandResult.error("usage"); });
        SlashCommandInteractionEvent event = slashEvent("usage", false);

        service.processCommand(event);

        verify(event, times(1)).reply(anyString()); // before repliedEmbed(), which calls reply("") to reach the action
        assertEquals("Usage: usage <x>", repliedEmbed(event));
        verify(event.getHook(), never()).editOriginal(anyString());
    }

    private SlashCommandInteractionEvent slashEvent(String name, boolean acknowledged) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        User user = mock(User.class);
        when(user.getId()).thenReturn("u");
        when(event.getName()).thenReturn(name);
        when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(null);
        when(event.getUserLocale()).thenReturn(DiscordLocale.ENGLISH_US);
        when(event.getOption(anyString())).thenReturn(null);
        when(event.isAcknowledged()).thenReturn(acknowledged);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.reply(anyString())).thenReturn(reply);
        return event;
    }

    private static CoreSettings.Commands commands(String prefix) {
        return new CoreSettings.Commands(prefix, false, false, false, false);
    }
}
