package com.example.plugin;

import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The template ships with every example switched off in {@code config.yml}: a fresh copy must boot
 * and register nothing. These tests pin that contract and what turning a flag on actually does.
 */
class TemplateFeatureFlagsTest {

    @TempDir Path dataFolder;

    private TemplatePlugin plugin;
    private PluginContext context;
    private PluginCommandAdapter commands;
    private PluginPermissionAdapter permissions;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        commands = mock(PluginCommandAdapter.class);
        permissions = mock(PluginPermissionAdapter.class);
        when(permissions.getRegisteredPermissions()).thenReturn(java.util.Set.of());

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));

        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> i.getArgument(1));

        context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn("plugin-template");
        when(context.getPluginName()).thenReturn("Plugin Template");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("template-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(mock(DiscordAPI.class));
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getCommands()).thenReturn(commands);
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(mock(PluginDataStorageAdapter.class));

        plugin = new TemplatePlugin();
    }

    /** The feature flags are read in onLoad, so a test configures the flags first. */
    private void enable() {
        plugin.onLoad(context);
        plugin.onPreEnable();
        plugin.onEnable();
    }

    private List<String> registeredPermissionNames() {
        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());
        return captor.getAllValues().stream().map(Permission::getName).toList();
    }

    @Test
    void aFreshCopyRegistersNoCommand() {
        enable();

        verify(commands, never()).registerCommand(any(Consumer.class));
    }

    @Test
    void theBasePermissionsAreAlwaysDeclaredAndNamespaced() {
        enable();

        List<String> names = registeredPermissionNames();
        assertEquals(List.of("plugin-template.use", "plugin-template.admin"), names,
                "the command permission only comes with the example commands");
        assertTrue(names.stream().allMatch(name -> name.startsWith("plugin-template.")),
                "a plugin must namespace its permissions with its own id");
    }

    @Test
    void turningTheExampleCommandsOnRegistersTheCommandAndItsPermission() {
        when(configuration.getBoolean("features.example_commands", false)).thenReturn(true);

        enable();

        assertTrue(registeredPermissionNames().contains("plugin-template.command.example"));

        ArgumentCaptor<Consumer<CommandBuilder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(commands).registerCommand(captor.capture());
        CommandBuilder builder = mock(CommandBuilder.class, invocation ->
                CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null);
        captor.getValue().accept(builder);
        verify(builder).name("template-example");
        verify(builder).description("commands.example");
    }

    @Test
    void messagesAreIgnoredWhileTheEventExampleIsOff() {
        enable();
        MessageReceivedEvent event = messageEvent("hello template", false);

        plugin.onMessageReceived(event);

        verify(event, never()).getAuthor();
    }

    @Test
    void aBotMessageIsNeverAnswered() {
        when(configuration.getBoolean("features.example_events", false)).thenReturn(true);
        when(configuration.getBoolean("features.respond_to_mentions", false)).thenReturn(true);
        enable();
        MessageReceivedEvent event = messageEvent("hello template", true);

        plugin.onMessageReceived(event);

        verify(event.getMessage(), never()).addReaction(any());
    }

    @Test
    void theTemplateReactsOnlyWhenBothFlagsAreOnAndTheMessageMatches() {
        when(configuration.getBoolean("features.example_events", false)).thenReturn(true);
        when(configuration.getBoolean("features.respond_to_mentions", false)).thenReturn(true);
        enable();

        MessageReceivedEvent unrelated = messageEvent("good morning", false);
        plugin.onMessageReceived(unrelated);
        verify(unrelated.getMessage(), never()).addReaction(any());

        MessageReceivedEvent mentioning = messageEvent("hey TEMPLATE, hello", false);
        plugin.onMessageReceived(mentioning);
        verify(mentioning.getMessage()).addReaction(any());
    }

    @SuppressWarnings("unchecked")
    private MessageReceivedEvent messageEvent(String content, boolean fromBot) {
        User author = mock(User.class);
        when(author.isBot()).thenReturn(fromBot);
        when(author.getName()).thenReturn("tester");

        Message message = mock(Message.class);
        when(message.getContentRaw()).thenReturn(content);
        when(message.addReaction(any())).thenReturn(mock(RestAction.class));

        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(message);
        return event;
    }
}
