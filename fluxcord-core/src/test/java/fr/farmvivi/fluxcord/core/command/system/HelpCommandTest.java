package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The built-in {@code help} command through the real command service and language manager, on the console
 * transport (its output is plain text, so the rendered help can be asserted directly).
 */
class HelpCommandTest {

    private final SimpleEventManager events = new SimpleEventManager();
    private final JDA jda = mock(JDA.class);
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private SimpleCommandService service;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        PermissionManager permissions = mock(PermissionManager.class);
        when(permissions.hasPermission(any(), any(), any())).thenReturn(true);
        service = new SimpleCommandService(events, new SimpleLanguageManager(Locale.US), permissions,
                new CoreSettings.Commands("!", true, false, false, false), mock(DataStorageManager.class));
        service.setJDA(jda);
        service.enable(); // registers the help command (commands.system.help = true)

        register("play", "Plays music", "Music", b -> b.aliases("p").permission("music.play")
                .stringOption("query", "What to play", true).integerOption("volume", "How loud", false));
        register("skip", "Skips the track", "Music", b -> { });
        register("ping", "Pong", "Utility", b -> { });
        Command admin = new SimpleCommandBuilder().name("admin").description("Admin tools").category("Utility")
                .subcommand(s -> s.name("kick").description("Kicks").executor((c, cmd) -> CommandResult.success()))
                .build();
        service.getRegistry().register(admin, null);
    }

    private void register(String name, String description, String category, java.util.function.Consumer<fr.farmvivi.fluxcord.api.command.CommandBuilder> more) {
        fr.farmvivi.fluxcord.api.command.CommandBuilder builder = new SimpleCommandBuilder().name(name).description(description).category(category);
        more.accept(builder);
        service.getRegistry().register(builder.executor((c, cmd) -> CommandResult.success()).build(), null);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        service.disable();
        events.shutdown();
    }

    private String run(String line) {
        console.reset();
        service.processCommand(new ConsoleCommandEvent(jda, line));
        return console.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void generalHelpGroupsEnabledCommandsByCategory() {
        service.getRegistry().disableCommand("ping");
        String out = run("help");

        assertTrue(out.contains("=== Available Commands ==="), out);
        assertTrue(out.contains("Type `help <command>` or `help <category>`"), "console prefix in the description: " + out);
        assertTrue(out.indexOf("Music: `play` - Plays music") < out.indexOf("`skip` - Skips the track"), out);
        assertTrue(out.contains("System: `help` - Shows information about available commands"), out);
        assertTrue(out.contains("`admin` - Admin tools"), out);
        assertFalse(out.contains("ping"), "disabled commands are hidden");
        assertTrue(out.indexOf("Music:") < out.indexOf("System:") && out.indexOf("System:") < out.indexOf("Utility:"), "categories sorted");
    }

    @Test
    void commandHelpShowsUsageOptionsAliasesAndPermission() {
        String out = run("help p"); // by alias

        assertTrue(out.contains("=== Command: play ==="), out);
        assertTrue(out.contains("Plays music"), out);
        assertTrue(out.contains("Usage: **Usage:** `play <query> [volume]`"), "console usage has no prefix: " + out);
        assertTrue(out.contains("Category: Music"), out);
        assertTrue(out.contains("Aliases: p"), out);
        assertTrue(out.contains("Permission: music.play"), out);
        assertTrue(out.contains("**query** (required): What to play"), out);
        assertTrue(out.contains("**volume**: How loud"), out);
        assertFalse(out.contains("Subcommands"), out);

        String admin = run("help admin");
        assertTrue(admin.contains("Subcommands: **kick**: Kicks"), admin);
    }

    @Test
    void unknownCommandOrCategoryIsAnError() {
        assertTrue(run("help nope").contains("Command not found: nope"));
        assertTrue(run("help ?").contains("=== Command: help ==="), "? is the alias of help");
    }

    @Test
    void categoryHelpListsItsCommandsSorted() {
        String out = run("help music"); // a category name where a command name is expected, any case

        assertTrue(out.contains("=== Category: Music ==="), out);
        assertTrue(out.contains("Commands in this category:"), out);
        assertTrue(out.indexOf("play: Plays music") < out.indexOf("skip: Skips the track"), out);
        assertFalse(out.contains("ping"), out);
        assertTrue(run("help Utility").contains("=== Category: Utility ==="));
    }

    @Test
    void categoriesAreAutocompleted() {
        var provider = service.getRegistry().getCommand("help").orElseThrow().getOptions().stream()
                .filter(o -> o.getName().equals("category")).findFirst().orElseThrow().getAutocompleteProvider();
        List<String> all = provider.suggest(new AutocompleteContext("", null, "u", Map.of())).stream().map(c -> c.name()).toList();
        assertEquals(List.of("Music", "System", "Utility"), all.stream().sorted().toList());
        assertEquals(List.of("Music"), provider.suggest(new AutocompleteContext("mus", null, "u", Map.of())).stream().map(c -> c.name()).toList());
    }

    @Test
    void generalHelpIsSplitWhenItWouldExceedDiscordsEmbedLimits() {
        IntStream.range(0, 30).forEach(i -> register("cmd" + i, "d".repeat(200), "Cat" + i, b -> { }));

        String out = run("help");

        assertTrue(out.contains("=== Available Commands (Continued) ==="), "a second embed follows the first: " + out);
        assertTrue(out.contains("`cmd29`"), "every command is listed somewhere");
    }
}
