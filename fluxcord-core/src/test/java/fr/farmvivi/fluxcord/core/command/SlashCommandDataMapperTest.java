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
}
