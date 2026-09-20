package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.core.Fluxcord;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.JDA.ShardInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The built-in {@code version} and {@code shutdown} commands, from the console and from Discord. */
class VersionAndShutdownCommandTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final JDA jda = mock(JDA.class);
    private final SimplePermissionManager permissions = new SimplePermissionManager(events,
            new SimpleDataStorageManager(mock(DataStorage.class)), Set.of("operator"));
    private final CountDownLatch shutdownRequested = new CountDownLatch(1);
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        when(jda.getGatewayPing()).thenReturn(42L);
        when(jda.getShardInfo()).thenReturn(new ShardInfo(0, 1));
        service = new SimpleCommandService(events, new SimpleLanguageManager(Locale.US), permissions,
                new CoreSettings.Commands("!", false, true, true, false), new SimpleDataStorageManager(mock(DataStorage.class)));
        service.setShutdownHandler(shutdownRequested::countDown);
        service.setJDA(jda);
        service.enable();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        service.disable();
        events.shutdown();
    }

    private String console(String line) {
        console.reset();
        service.processCommand(new ConsoleCommandEvent(jda, line));
        return console.toString(StandardCharsets.UTF_8);
    }

    @Test
    void versionShowsBuildRuntimeAndGatewayFacts() {
        String out = console("version");
        assertTrue(out.contains(Fluxcord.NAME) && out.contains(Fluxcord.VERSION), out);
        assertTrue(out.contains(System.getProperty("java.version")), out);
        assertTrue(out.contains("42ms"), out);
        assertTrue(out.contains("0/1"), "shard info: " + out);
        assertTrue(out.contains(Fluxcord.PRODUCTION ? "Production" : "Development"), out);
        assertEquals(out.length(), console("about").length(), "alias");
    }

    @Test
    void shutdownFromTheConsoleRepliesThenHandsOverToTheRuntime() throws Exception {
        String out = console("shutdown");
        assertTrue(out.contains("shutting down"), out);
        assertTrue(shutdownRequested.await(3, TimeUnit.SECONDS), "handler called after the grace delay");
    }

    @Test
    void shutdownFromDiscordIsForOperatorsOnly() throws Exception {
        SlashCommandInteractionEvent nobody = slash("shutdown", "someone");
        service.processCommand(nobody);
        assertTrue(repliedEmbed(nobody).toLowerCase().contains("operator"), "refused");
        assertFalse(shutdownRequested.await(1500, TimeUnit.MILLISECONDS), "no shutdown scheduled");

        SlashCommandInteractionEvent op = slash("shutdown", "operator");
        service.processCommand(op);
        assertTrue(repliedEmbed(op).contains("shutting down"));
        assertTrue(shutdownRequested.await(3, TimeUnit.SECONDS));
    }

    private SlashCommandInteractionEvent slash(String name, String userId) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        when(event.getName()).thenReturn(name);
        when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(null);
        when(event.getJDA()).thenReturn(jda);
        when(event.getUserLocale()).thenReturn(DiscordLocale.ENGLISH_US);
        when(event.getOption(anyString())).thenReturn(null);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.reply(anyString())).thenReturn(reply);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static String repliedEmbed(SlashCommandInteractionEvent event) {
        ReplyCallbackAction action = event.reply("");
        ArgumentCaptor<Collection<MessageEmbed>> embeds = ArgumentCaptor.forClass(Collection.class);
        verify(action).addEmbeds(embeds.capture());
        MessageEmbed embed = embeds.getValue().iterator().next();
        return embed.getDescription() != null ? embed.getDescription() : String.valueOf(embed.getTitle());
    }
}
