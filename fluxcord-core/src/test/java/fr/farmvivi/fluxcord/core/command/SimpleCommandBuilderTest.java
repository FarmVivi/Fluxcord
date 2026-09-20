package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.command.option.OptionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The command builder: every option type, their constraints, metadata, subcommands and the build-time rules. */
class SimpleCommandBuilderTest {

    private static Map<String, CommandOption<?>> options(Command command) {
        return command.getOptions().stream().collect(Collectors.toMap(CommandOption::getName, Function.identity()));
    }

    @Test
    void metadataIsCarriedOverAndDefaulted() {
        Command command = new SimpleCommandBuilder().name("Play").description("Plays")
                .category("Music").permission("music.play").translationKey("cmd.play")
                .alias("p").aliases("pl", "pla").guildOnly(true).guilds("g1", "g2").enabled(false).cooldown(5)
                .execute((c, cmd) -> CommandResult.success()).build();

        assertEquals("Play", command.getName());
        assertEquals("Plays", command.getDescription());
        assertEquals("Music", command.getCategory());
        assertEquals("music.play", command.getPermission());
        assertEquals("cmd.play", command.getTranslationKey());
        assertEquals(Set.of("p", "pl", "pla"), command.getAliases());
        assertTrue(command.isGuildOnly());
        assertEquals(Set.of("g1", "g2"), command.getGuildIds());
        assertFalse(command.isEnabled());
        assertEquals(5, command.getCooldown());
        assertNull(command.getGroup());
        assertNull(command.getParent());
        assertFalse(command.isSubcommand());
        assertEquals("Play", command.getFullName());

        Command minimal = new SimpleCommandBuilder().name("x").description("d").executor((c, cmd) -> CommandResult.success()).build();
        assertEquals("General", minimal.getCategory(), "default category");
        assertEquals("command.x", minimal.getTranslationKey(), "default translation key");
        assertTrue(minimal.isEnabled());
        assertEquals(0, minimal.getCooldown());
        assertTrue(minimal.getAliases().isEmpty());
        assertTrue(minimal.execute(null).isSuccess());
    }

    @Test
    void everyOptionTypeIsAvailable() {
        Command command = new SimpleCommandBuilder().name("all").description("d")
                .stringOption("s", "s", true)
                .stringOption("sv", "s", false, (String v) -> v.startsWith("a"))
                .stringOption("sc", "s", false, new OptionChoice<>("A", "a"), new OptionChoice<>("B", "b"))
                .stringOption("sa", "s", false, (String partial) -> List.of(new OptionChoice<>(partial, partial)))
                .integerOption("i", "i", true)
                .integerOption("iv", "i", false, (Integer v) -> v > 0)
                .integerOption("ir", "i", false, 1, 10)
                .integerOption("ic", "i", false, new OptionChoice<>("One", 1), new OptionChoice<>("Two", 2))
                .booleanOption("b", "b", false)
                .userOption("u", "u", false)
                .channelOption("c", "c", false)
                .roleOption("r", "r", false)
                .mentionableOption("m", "m", false)
                .numberOption("n", "n", false)
                .numberOption("nr", "n", false, 0.5, 2.5)
                .attachmentOption("a", "a", false)
                .attachmentOption("af", "a", false, "image", "pdf")
                .option(OptionType.ROLE, "raw", "raw", true)
                .executor((c, cmd) -> CommandResult.success()).build();

        Map<String, CommandOption<?>> o = options(command);
        assertEquals(18, o.size());
        assertEquals(List.of("s", "sv", "sc", "sa", "i", "iv", "ir", "ic", "b", "u", "c", "r", "m", "n", "nr", "a", "af", "raw"),
                command.getOptions().stream().map(CommandOption::getName).toList(), "declaration order is kept");

        assertEquals(OptionType.STRING, o.get("s").getType());
        assertTrue(o.get("s").isRequired());
        assertNotNull(o.get("sv").getValidator());
        assertEquals(2, o.get("sc").getChoices().size());
        assertNotNull(o.get("sa").getAutocompleteProvider());
        assertEquals(OptionType.INTEGER, o.get("i").getType());
        assertEquals(1, o.get("ir").getMinValue());
        assertEquals(10, o.get("ir").getMaxValue());
        assertEquals(List.of(1, 2), o.get("ic").getChoices().stream().map(OptionChoice::value).toList());
        assertEquals(OptionType.BOOLEAN, o.get("b").getType());
        assertEquals(OptionType.USER, o.get("u").getType());
        assertEquals(OptionType.CHANNEL, o.get("c").getType());
        assertEquals(OptionType.ROLE, o.get("r").getType());
        assertEquals(OptionType.MENTIONABLE, o.get("m").getType());
        assertEquals(OptionType.NUMBER, o.get("n").getType());
        assertEquals(0.5, o.get("nr").getMinValue());
        assertEquals(2.5, o.get("nr").getMaxValue());
        assertEquals(OptionType.ATTACHMENT, o.get("a").getType());
        assertEquals(List.of("image", "pdf"), o.get("af").getFileTypes());
        assertEquals(OptionType.ROLE, o.get("raw").getType());
        assertTrue(o.get("raw").isRequired());
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatorsChoicesAndBoundsDriveIsValid() {
        Command command = new SimpleCommandBuilder().name("v").description("d")
                .stringOption("sv", "s", false, (String v) -> v.startsWith("a"))
                .stringOption("sc", "s", false, new OptionChoice<>("A", "a"))
                .integerOption("ir", "i", false, 1, 10)
                .integerOption("req", "i", true)
                .executor((c, cmd) -> CommandResult.success()).build();
        Map<String, CommandOption<?>> o = options(command);

        assertTrue(((CommandOption<String>) o.get("sv")).isValid("abc"));
        assertFalse(((CommandOption<String>) o.get("sv")).isValid("xbc"));
        assertTrue(((CommandOption<String>) o.get("sc")).isValid("a"));
        assertFalse(((CommandOption<String>) o.get("sc")).isValid("z"));
        assertTrue(((CommandOption<Integer>) o.get("ir")).isValid(10));
        assertFalse(((CommandOption<Integer>) o.get("ir")).isValid(11));
        assertTrue(((CommandOption<Integer>) o.get("ir")).isValid(null), "optional: null is fine");
        assertFalse(((CommandOption<Integer>) o.get("req")).isValid(null), "required: null is not");
    }

    @Test
    void subcommandsAreLinkedToTheirParent() {
        Command command = new SimpleCommandBuilder().name("admin").description("d").group("mod")
                .subcommand(s -> s.name("kick").description("k").userOption("who", "w", true).executor((c, cmd) -> CommandResult.success()))
                .subcommand(s -> s.name("ban").description("b").executor((c, cmd) -> CommandResult.error("no")))
                .build();

        assertEquals("mod", command.getGroup());
        assertEquals(List.of("kick", "ban"), command.getSubcommands().stream().map(Command::getName).toList());
        Command kick = command.getSubcommands().get(0);
        assertTrue(kick.isSubcommand());
        assertSame(command, kick.getParent());
        assertEquals("admin/kick", kick.getFullName());
        assertEquals(1, kick.getOptions().size());
        assertFalse(command.execute(null).isSuccess(), "a parent has no executor of its own");
        assertFalse(command.getSubcommands().get(1).execute(null).isSuccess());
    }

    @Test
    void buildRulesAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new SimpleCommandBuilder().description("d").executor((c, cmd) -> CommandResult.success()).build(), "name required");
        assertThrows(IllegalArgumentException.class, () -> new SimpleCommandBuilder().name("x").executor((c, cmd) -> CommandResult.success()).build(), "description required");
        assertThrows(IllegalArgumentException.class, () -> new SimpleCommandBuilder().name("x").description("d").build(), "executor required without subcommands");
        assertThrows(IllegalArgumentException.class, () -> new SimpleCommandBuilder().name("x").description("d")
                .subcommand(s -> s.name("y").description("d").executor((c, cmd) -> CommandResult.success()))
                .executor((c, cmd) -> CommandResult.success()).build(), "no executor with subcommands");
    }
}
