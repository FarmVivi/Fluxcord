package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteProvider;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The JDA {@code CommandData} sent on sync mirrors the command model, for top-level and subcommand options alike. */
class SlashCommandDataMapperTest {

    @Test
    void topLevelOptionsCarryBoundsChoicesAndAutocomplete() {
        Command command = new SimpleCommandBuilder().name("Play").description("plays")
                .stringOption("query", "what", true, (AutocompleteProvider<String>) ctx -> List.of())
                .integerOption("volume", "how loud", false, 0, 200)
                .stringOption("mode", "m", false, new OptionChoice<>("Track", "track"), new OptionChoice<>("Queue", "queue"))
                .permission("music.play").guildOnly(true)
                .executor((c, cmd) -> CommandResult.success()).build();

        SlashCommandData data = (SlashCommandData) SlashCommandDataMapper.toCommandData(command);

        assertEquals("play", data.getName(), "Discord requires lower-case names");
        assertEquals(DefaultMemberPermissions.DISABLED.getPermissionsRaw(), data.getDefaultPermissions().getPermissionsRaw());
        assertEquals(Set.of(InteractionContextType.GUILD), data.getContexts());
        List<OptionData> options = data.getOptions();
        assertEquals(3, options.size());
        assertTrue(options.get(0).isRequired());
        assertTrue(options.get(0).isAutoComplete());
        assertEquals(OptionType.INTEGER, options.get(1).getType());
        assertEquals(0L, options.get(1).getMinValue());
        assertEquals(200L, options.get(1).getMaxValue());
        assertEquals(List.of("track", "queue"), options.get(2).getChoices().stream().map(c -> c.getAsString()).toList());
    }

    @Test
    void subcommandOptionsAreMappedLikeTopLevelOnes() {
        Command command = new SimpleCommandBuilder().name("perm").description("p")
                .subcommand(sub -> sub.name("set").description("s")
                        .stringOption("node", "n", true, (AutocompleteProvider<String>) ctx -> List.of())
                        .stringOption("scope", "s", false, new OptionChoice<>("guild", "guild"), new OptionChoice<>("global", "global"))
                        .executor((c, cmd) -> CommandResult.success()))
                .build();

        SlashCommandData data = (SlashCommandData) SlashCommandDataMapper.toCommandData(command);

        assertEquals(DefaultMemberPermissions.ENABLED.getPermissionsRaw(), data.getDefaultPermissions().getPermissionsRaw());
        assertEquals(3, data.getContexts().size(), "usable in guilds, DMs and group DMs");
        SubcommandData set = data.getSubcommands().get(0);
        assertEquals("set", set.getName());
        assertTrue(set.getOptions().get(0).isAutoComplete(), "autocomplete must survive on subcommand options");
        assertEquals(2, set.getOptions().get(1).getChoices().size(), "choices must survive on subcommand options");
    }

    @Test
    void numberBoundsIntegerChoicesLengthsAndFileTypesAreMapped() {
        Command command = new SimpleCommandBuilder().name("up").description("u")
                .numberOption("ratio", "r", false, 0.5, 2.0)
                .integerOption("level", "l", false, new OptionChoice<>("Low", 1), new OptionChoice<>("High", 3))
                .attachmentOption("file", "f", false, "image", "video", "audio", "pdf")
                .executor((c, cmd) -> CommandResult.success()).build();

        SlashCommandData data = (SlashCommandData) SlashCommandDataMapper.toCommandData(command);

        List<OptionData> options = data.getOptions();
        assertEquals(OptionType.NUMBER, options.get(0).getType());
        assertEquals(0.5, options.get(0).getMinValue());
        assertEquals(2.0, options.get(0).getMaxValue());
        assertEquals(List.of(1L, 3L), options.get(1).getChoices().stream().map(c -> c.getAsLong()).toList());
        assertEquals(OptionType.ATTACHMENT, options.get(2).getType());
        assertEquals(4, options.get(2).getFileTypes().size(), "image/video/audio keywords plus an extension");
    }

    @Test
    void aGroupedCommandNestsItsSubcommandsUnderTheGroup() {
        Command command = new SimpleCommandBuilder().name("playlist").description("p").group("Manage")
                .subcommand(sub -> sub.name("add").description("a").executor((c, cmd) -> CommandResult.success()))
                .subcommand(sub -> sub.name("remove").description("r").executor((c, cmd) -> CommandResult.success()))
                .build();

        SlashCommandData data = (SlashCommandData) SlashCommandDataMapper.toCommandData(command);

        assertTrue(data.getSubcommands().isEmpty());
        assertEquals(1, data.getSubcommandGroups().size());
        assertEquals("manage", data.getSubcommandGroups().get(0).getName());
        assertEquals(List.of("add", "remove"), data.getSubcommandGroups().get(0).getSubcommands().stream().map(SubcommandData::getName).toList());
    }
}
