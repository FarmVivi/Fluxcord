package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.api.audio.events.AudioSendHandlerRegisteredEvent;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires pour la classe AudioServiceImpl.
 */
public class AudioServiceImplTest {
    private AudioServiceImpl audioService;
    private EventManager mockEventManager;
    private Guild mockGuild;
    private Plugin mockPlugin;
    private AudioManager mockAudioManager;
    private AudioSendHandler mockSendHandler;

    @BeforeEach
    public void setUp() {
        // Crée les mocks
        mockEventManager = Mockito.mock(EventManager.class);
        mockGuild = Mockito.mock(Guild.class);
        mockPlugin = Mockito.mock(Plugin.class);
        mockAudioManager = Mockito.mock(AudioManager.class);
        mockSendHandler = Mockito.mock(AudioSendHandler.class);

        // Configure les mocks
        when(mockGuild.getId()).thenReturn("123456789");
        when(mockGuild.getName()).thenReturn("Test Guild");
        when(mockGuild.getAudioManager()).thenReturn(mockAudioManager);
        when(mockPlugin.getName()).thenReturn("TestPlugin");
        when(mockPlugin.getId()).thenReturn("test-plugin");

        // Crée le service audio
        audioService = new AudioServiceImpl(mockEventManager);
    }

    @Test
    public void testRegisterSendHandler() {
        // Enregistre un handler d'envoi
        audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 80, 50);

        // Vérifie que l'événement a été émis
        ArgumentCaptor<AudioSendHandlerRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(AudioSendHandlerRegisteredEvent.class);
        verify(mockEventManager).fireEvent(eventCaptor.capture());

        // Vérifie les détails de l'événement
        AudioSendHandlerRegisteredEvent event = eventCaptor.getValue();
        assertEquals(mockGuild, event.getGuild());
        assertEquals(mockPlugin, event.getPlugin());
        assertEquals(mockSendHandler, event.getHandler());
        assertEquals(80, event.getVolume());
        assertEquals(50, event.getPriority());

        // Vérifie que le handler est bien enregistré
        assertTrue(audioService.hasActiveSendHandler(mockGuild, mockPlugin));
    }

    @Test
    public void testDeregisterSendHandler() {
        // Enregistre d'abord un handler
        audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 80, 50);

        // Réinitialise les mocks pour vérifier seulement le désenregistrement
        reset(mockEventManager);

        // Désenregistre le handler
        audioService.deregisterSendHandler(mockGuild, mockPlugin);

        // Vérifie que le handler est bien désenregistré
        assertFalse(audioService.hasActiveSendHandler(mockGuild, mockPlugin));

        // Vérifie que l'événement approprié a été émis
        verify(mockEventManager).fireEvent(any());
    }

    @Test
    public void testSetVolume() {
        // Enregistre d'abord un handler
        audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 50, 50);

        // Réinitialise les mocks
        reset(mockEventManager);

        // Change le volume
        audioService.setVolume(mockGuild, mockPlugin, 75);

        // Vérifie qu'un événement de changement de volume a été émis
        verify(mockEventManager).fireEvent(any());
    }

    @Test
    public void testInvalidVolumeThrowsException() {
        // Vérifie que des volumes invalides lèvent une exception
        assertThrows(IllegalArgumentException.class, () -> {
            audioService.setVolume(mockGuild, mockPlugin, -10);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            audioService.setVolume(mockGuild, mockPlugin, 110);
        });
    }

    @Test
    public void testInvalidPriorityThrowsException() {
        // Vérifie que des priorités invalides lèvent une exception
        assertThrows(IllegalArgumentException.class, () -> {
            audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 50, -10);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 50, 110);
        });
    }

    @Test
    public void testCloseAllConnectionsForPlugin() {
        // Enregistre des handlers pour plusieurs guildes
        Guild mockGuild2 = Mockito.mock(Guild.class);
        AudioManager mockAudioManager2 = Mockito.mock(AudioManager.class);

        when(mockGuild2.getId()).thenReturn("987654321");
        when(mockGuild2.getName()).thenReturn("Test Guild 2");
        when(mockGuild2.getAudioManager()).thenReturn(mockAudioManager2);

        audioService.registerSendHandler(mockGuild, mockPlugin, mockSendHandler, 80, 50);
        audioService.registerSendHandler(mockGuild2, mockPlugin, mockSendHandler, 80, 50);

        // Réinitialise les mocks
        reset(mockEventManager);

        // Ferme toutes les connexions pour le plugin
        audioService.closeAllConnectionsForPlugin(mockPlugin);

        // Vérifie que les handlers sont désenregistrés
        assertFalse(audioService.hasActiveSendHandler(mockGuild, mockPlugin));
        assertFalse(audioService.hasActiveSendHandler(mockGuild2, mockPlugin));
    }
}