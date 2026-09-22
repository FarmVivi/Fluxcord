package fr.farmvivi.fluxcord.examples.commands;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The command example is documentation that runs: what it shows must actually work. These tests
 * pin the behaviour a plugin author would copy — the config toggles, the declarative cooldown and
 * permission, and the two reply styles.
 */
class CommandExamplePluginTest {

    @TempDir Path dataFolder;

    private CommandExamplePlugin plugin;
    private PluginCommandAdapter commands;
    private PluginPermissionAdapter permissions;
    private Configuration configuration;
    private CommandContext context;

    /** A command registration replayed against a recording builder. */
    private record Registered(String name, String description, String permission, int cooldown,
                              List<String> options, BiFunction<CommandContext, Command, CommandResult> executor) {
    }

    @BeforeEach
    void setUp() {
        commands = mock(PluginCommandAdapter.class);
        permissions = mock(PluginPermissionAdapter.class);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        when(language.getString(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));

        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getBoolean("enabled", false)).thenReturn(true);

        PluginContext pluginContext = mock(PluginContext.class);
        when(pluginContext.getPluginId()).thenReturn("plugin-example-commands");
        when(pluginContext.getPluginName()).thenReturn("Command Example");
        when(pluginContext.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(pluginContext.getLogger()).thenReturn(LoggerFactory.getLogger("example-test"));
        when(pluginContext.getEventManager()).thenReturn(mock(EventManager.class));
        when(pluginContext.getDiscordAPI()).thenReturn(mock(DiscordAPI.class));
        when(pluginContext.getConfiguration()).thenReturn(configuration);
        when(pluginContext.getDataFolder()).thenReturn(dataFolder.toString());
        when(pluginContext.getCommands()).thenReturn(commands);
        when(pluginContext.getPermissions()).thenReturn(permissions);
        when(pluginContext.getLanguage()).thenReturn(language);

        User user = mock(User.class);
        when(user.getId()).thenReturn("u1");
        when(user.getName()).thenReturn("tester");
        context = mock(CommandContext.class);
        when(context.getUser()).thenReturn(user);

        plugin = new CommandExamplePlugin();
        plugin.onLoad(pluginContext);
    }

    @SuppressWarnings("unchecked")
    private List<Registered> registrations() {
        ArgumentCaptor<Consumer<CommandBuilder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(commands, atLeast(0)).registerCommand(captor.capture());

        List<Registered> registered = new ArrayList<>();
        for (Consumer<CommandBuilder> registration : captor.getAllValues()) {
            Map<String, Object> seen = new HashMap<>();
            List<String> options = new ArrayList<>();
            CommandBuilder builder = mock(CommandBuilder.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.endsWith("Option")) {
                    options.add(invocation.getArgument(0));
                } else if (invocation.getArguments().length > 0) {
                    seen.putIfAbsent(method, invocation.getArgument(0));
                }
                return CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null;
            });
            registration.accept(builder);
            registered.add(new Registered(
                    (String) seen.get("name"),
                    (String) seen.get("description"),
                    (String) seen.get("permission"),
                    seen.get("cooldown") instanceof Integer cooldown ? cooldown : 0,
                    options,
                    (BiFunction<CommandContext, Command, CommandResult>) seen.get("executor")));
        }
        return registered;
    }

    private Registered registration(String name) {
        return registrations().stream()
                .filter(registered -> name.equals(registered.name()))
                .findFirst()
                .orElseGet(() -> fail(name + " was not registered"));
    }

    private CommandResult run(String name) {
        return registration(name).executor().apply(context, mock(Command.class));
    }

    @Test
    void aDisabledPluginRegistersNothing() {
        when(configuration.getBoolean("enabled", false)).thenReturn(false);

        plugin.onEnable();

        verifyNoInteractions(commands);
        verifyNoInteractions(permissions);
    }

    @Test
    void thePermissionsAreDeclaredWithTheirDefaults() {
        plugin.onEnable();

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());

        Map<String, PermissionDefault> declared = new HashMap<>();
        captor.getAllValues().forEach(permission -> declared.put(permission.getName(), permission.getDefault()));
        assertEquals(Map.of(
                CommandExamplePlugin.USE_PERMISSION, PermissionDefault.TRUE,
                CommandExamplePlugin.ADMIN_PERMISSION, PermissionDefault.OP), declared);
    }

    @Test
    void everyCommandCanBeTurnedOffIndividually() {
        plugin.onEnable();
        assertEquals(List.of("ping", "echo", "info", "admin"),
                registrations().stream().map(Registered::name).toList());

        setUp();
        when(configuration.getBoolean("commands.echo.enabled", true)).thenReturn(false);
        when(configuration.getBoolean("commands.admin.enabled", true)).thenReturn(false);
        plugin.onEnable();

        assertEquals(List.of("ping", "info"), registrations().stream().map(Registered::name).toList());
    }

    @Test
    void theCooldownIsDeclaredSoTheCoreEnforcesIt() {
        when(configuration.getInt("commands.ping.cooldown", 0)).thenReturn(3);
        when(configuration.getInt("commands.echo.cooldown", 0)).thenReturn(5);

        plugin.onEnable();

        assertEquals(3, registration("ping").cooldown());
        assertEquals(5, registration("echo").cooldown(), "the config key must reach the builder");
        assertEquals(0, registration("info").cooldown());
    }

    @Test
    void theAdminCommandCarriesItsPermission() {
        plugin.onEnable();

        assertEquals(CommandExamplePlugin.ADMIN_PERMISSION, registration("admin").permission());
        assertNull(registration("ping").permission(), "the other commands are open");
    }

    @Test
    void pingRepliesWithAnEmbedOrPlainTextDependingOnTheConfig() {
        plugin.onEnable();

        assertEquals(CommandResult.success().isSuccess(), run("ping").isSuccess());
        verify(context).replyEmbed(any(EmbedBuilder.class));

        setUp();
        when(configuration.getBoolean("responses.use_embeds", true)).thenReturn(false);
        plugin.onEnable();
        run("ping");

        verify(context).reply("ping.response");
        verify(context, never()).replyEmbed(any());
    }

    @Test
    void echoRefusesAnEmptyOrOversizedMessage() {
        when(configuration.getInt("commands.echo.max_length", 200)).thenReturn(5);
        plugin.onEnable();

        when(context.getOption("message", "")).thenReturn("");
        assertFalse(run("echo").isSuccess());
        verify(context).reply("echo.no_message");

        when(context.getOption("message", "")).thenReturn("way too long");
        assertFalse(run("echo").isSuccess());
        verify(context).reply("echo.too_long");

        when(context.getOption("message", "")).thenReturn("hi");
        assertTrue(run("echo").isSuccess());
        verify(context).replyEmbed(any(EmbedBuilder.class));
    }

    @Test
    void echoDeclaresItsRequiredOption() {
        plugin.onEnable();

        assertEquals(List.of("message"), registration("echo").options());
    }

    @Test
    void infoCountsTheCommandsRunSoFar() {
        plugin.onEnable();
        run("ping");
        run("ping");

        run("info");

        ArgumentCaptor<EmbedBuilder> captor = ArgumentCaptor.forClass(EmbedBuilder.class);
        verify(context, times(3)).replyEmbed(captor.capture()); // two pings, then info
        String description = captor.getValue().build().getDescription();
        assertNotNull(description);
        assertTrue(description.contains("info.version"), description);
        assertTrue(description.contains("info.commands_executed"), description);
    }

    @Test
    void theEmbedColourFallsBackWhenTheConfiguredOneIsNotAHexColour() {
        when(configuration.getString("responses.embed_color", "#7289DA")).thenReturn("not-a-colour");
        plugin.onEnable();

        run("info");

        ArgumentCaptor<EmbedBuilder> captor = ArgumentCaptor.forClass(EmbedBuilder.class);
        verify(context).replyEmbed(captor.capture());
        assertEquals(java.awt.Color.BLUE.getRGB(), captor.getValue().build().getColorRaw() | 0xFF000000);
    }

    @Test
    void theAdminExecutorTrustsTheDeclaredPermission() {
        plugin.onEnable();

        assertTrue(run("admin").isSuccess());

        verify(context).replyEmbed(any(EmbedBuilder.class));
        verify(permissions, never()).hasPermission(anyString(), anyString());
    }
}
