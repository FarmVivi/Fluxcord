package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.audio.events.AudioVolumeChangedEvent;
import fr.farmvivi.fluxcord.api.command.exception.CommandException;
import fr.farmvivi.fluxcord.api.command.exception.CommandExecutionException;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.exception.CommandPermissionException;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDeleteEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDownloadEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileUploadEvent;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The rest of the command-side api surface: the adapter methods that only forward, the exception family
 * that carries the reason a command failed, and the audio and binary-storage events.
 *
 * <p>Thin as these are, they are what plugins compile against — a forward that silently drops its argument,
 * or an exception that loses the parameter it was about, is invisible until someone reads a useless error
 * message in Discord.
 */
class CommandApiSurfaceTest {

    private CommandService service;
    private PluginCommandAdapter commands;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getId()).thenReturn("music-plugin");
        service = mock(CommandService.class);
        commands = new PluginCommandAdapter(plugin, service);
    }

    @Test
    void theSynchronisationCallsReachTheService() {
        // A plugin that registers commands after boot has to ask for a sync itself; dropping the call
        // here would leave the command out of Discord with nothing in the log.
        Guild guild = mock(Guild.class);

        commands.synchronizeCommands();
        commands.synchronizeGlobalCommands();
        commands.synchronizeGuildCommands(guild);

        verify(service).synchronizeCommands();
        verify(service).synchronizeGlobalCommands();
        verify(service).synchronizeGuildCommands(guild);
    }

    @Test
    void cooldownQuestionsAreAnsweredByTheService() {
        when(service.isOnCooldown("u1", "play")).thenReturn(true);
        when(service.getRemainingCooldown("u1", "play")).thenReturn(7);

        assertTrue(commands.isOnCooldown("u1", "play"));
        assertEquals(7, commands.getRemainingCooldown("u1", "play"));
        assertFalse(commands.isOnCooldown("u1", "skip"), "unknown command, no cooldown");
    }

    @Test
    void thePrefixCanBeAskedGloballyOrPerGuild() {
        // Per-guild prefixes are stored, so the two answers legitimately differ.
        when(service.getPrefix()).thenReturn("!");
        when(service.getPrefix("g1")).thenReturn("?");

        assertEquals("!", commands.getPrefix());
        assertEquals("?", commands.getPrefix("g1"));
    }

    @Test
    void lookingUpACommandGoesThroughTheRegistry() {
        CommandRegistry registry = mock(CommandRegistry.class);
        Command play = mock(Command.class);
        when(service.getRegistry()).thenReturn(registry);
        when(registry.getCommand("play")).thenReturn(java.util.Optional.of(play));
        when(registry.getCommandByAlias("p")).thenReturn(java.util.Optional.of(play));

        assertEquals(java.util.Optional.of(play), commands.getCommand("play"));
        assertEquals(java.util.Optional.of(play), commands.getCommandByAlias("p"));
    }

    @Test
    void aParseFailureRemembersWhichParameterItWasAbout() {
        // Without the parameter, "invalid value" is all the user gets told.
        CommandParseException withParameter = new CommandParseException("not a number", "volume");

        assertEquals("not a number", withParameter.getMessage());
        assertEquals("volume", withParameter.getParameter());
        assertNull(new CommandParseException("no idea").getParameter());
    }

    @Test
    void aParseFailureCanCarryItsCause() {
        NumberFormatException cause = new NumberFormatException("for input string: loud");

        assertSame(cause, new CommandParseException("not a number", cause).getCause());
        CommandParseException full = new CommandParseException("not a number", "volume", cause);
        assertSame(cause, full.getCause());
        assertEquals("volume", full.getParameter());
    }

    @Test
    void theCommandExceptionFamilyIsOneHierarchy() {
        // The service catches CommandException; a sibling outside the family would escape to JDA's log.
        assertInstanceOf(CommandException.class, new CommandParseException("a"));
        assertInstanceOf(CommandException.class, new CommandExecutionException("b"));
        assertInstanceOf(CommandException.class, new CommandPermissionException("c", "music-plugin.admin"));
        assertEquals("b", new CommandExecutionException("b").getMessage());
        assertEquals("c", new CommandPermissionException("c", "music-plugin.admin").getMessage());
    }

    @Test
    void aVolumeChangeCarriesTheOldAndNewLevel() {
        Guild guild = mock(Guild.class);
        Plugin plugin = mock(Plugin.class);

        AudioVolumeChangedEvent event = new AudioVolumeChangedEvent(guild, plugin, 40, 80, false);

        assertSame(guild, event.getGuild());
        assertSame(plugin, event.getPlugin());
        assertEquals(40, event.getOldVolume());
        assertEquals(80, event.getNewVolume());
        assertFalse(event.isFading(), "a plain volume change, not a fade step");
        assertTrue(new AudioVolumeChangedEvent(guild, plugin, 80, 20, true).isFading(),
                "ducking reports its steps so a listener can ignore them");
    }

    @Test
    void theBinaryStorageEventsSayWhatHappenedToWhichFile() {
        BinaryStorageKey key = BinaryStorageKey.guild("g1", "covers/a.png");

        Object source = new byte[]{1, 2};
        FileUploadEvent upload = new FileUploadEvent(key, source, true);
        assertEquals(key, upload.getKey());
        assertSame(source, upload.getSource());
        assertTrue(upload.isOverwrite());
        assertFalse(upload.isCancelled());
        upload.setCancelled(true);
        assertTrue(upload.isCancelled(), "an upload can be vetoed before it is written");

        java.io.File destination = new java.io.File("a.png");
        FileDownloadEvent download = new FileDownloadEvent(key, destination);
        assertEquals(key, download.getKey());
        assertSame(destination, download.getDestination());
        FileDeleteEvent delete = new FileDeleteEvent(key);
        assertEquals(key, delete.getKey());
        delete.setCancelled(true);
        assertTrue(delete.isCancelled());
    }

    @Test
    void aCommandServiceThatKnowsNothingStillAnswers() {
        // The adapter must not add null checks of its own: the service is the authority, including when
        // it has nothing to say.
        when(service.getPrefix(anyString())).thenReturn(null);

        assertNull(commands.getPrefix("unknown-guild"));
    }
}
