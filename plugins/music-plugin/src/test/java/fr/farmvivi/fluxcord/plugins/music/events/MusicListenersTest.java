package fr.farmvivi.fluxcord.plugins.music.events;

import fr.farmvivi.fluxcord.plugins.music.MusicManager;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The JDA listeners of the music plugin. They are thin on purpose — what matters is that they
 * forward to the manager and that they survive being called before {@code onEnable} finished
 * (JDA can fire while the plugin is still starting).
 */
class MusicListenersTest {

    private MusicPlugin plugin;
    private MusicManager musicManager;

    @BeforeEach
    void setUp() {
        musicManager = mock(MusicManager.class);
        plugin = mock(MusicPlugin.class);
        when(plugin.getMusicManager()).thenReturn(musicManager);
    }

    @Test
    void voiceUpdatesReachTheManager() {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);

        new MusicVoiceListener(plugin).onGuildVoiceUpdate(event);

        verify(musicManager).handleVoiceUpdate(event);
    }

    @Test
    void readyRestoresEveryPersistedState() {
        ReadyEvent event = mock(ReadyEvent.class);
        JDA jda = mock(JDA.class);
        when(event.getJDA()).thenReturn(jda);

        new MusicReadyListener(plugin).onReady(event);

        verify(musicManager).restoreAllStates(jda);
    }

    @Test
    void theListenersDoNothingBeforeTheManagerExists() {
        when(plugin.getMusicManager()).thenReturn(null);
        ReadyEvent ready = mock(ReadyEvent.class);

        new MusicVoiceListener(plugin).onGuildVoiceUpdate(mock(GuildVoiceUpdateEvent.class));
        new MusicReadyListener(plugin).onReady(ready);

        verify(ready, never()).getJDA();
        verifyNoInteractions(musicManager);
    }

    @Test
    void aButtonFromAnotherPluginIsIgnored() {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        when(event.getComponentId()).thenReturn("some-other-plugin:whatever");

        new MusicButtonListener(plugin).onButtonInteraction(event);

        verify(event, never()).deferEdit();
        verify(event, never()).reply(any(String.class));
        verifyNoInteractions(musicManager);
    }
}
