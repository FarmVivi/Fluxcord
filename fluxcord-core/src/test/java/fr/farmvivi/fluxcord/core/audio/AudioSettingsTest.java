package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.core.config.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class AudioSettingsTest {

    private static YamlConfiguration config(File dir, String yaml) throws Exception {
        File file = new File(dir, "config.yml");
        Files.writeString(file.toPath(), yaml, StandardCharsets.UTF_8);
        return new YamlConfiguration(file);
    }

    @Test
    void defaultsWhenTheSectionIsAbsent(@TempDir File dir) throws Exception {
        AudioSettings settings = AudioSettings.fromConfig(config(dir, "discord:\n  token: x\n"));
        assertEquals(AudioSettings.DEFAULTS, settings);
        assertEquals(10, settings.fadeSteps());
        assertEquals(0.2f, settings.duckingFloor(), 1e-6);
    }

    @Test
    void readsTheAudioSection(@TempDir File dir) throws Exception {
        AudioSettings settings = AudioSettings.fromConfig(config(dir, "audio:\n  fade-duration-ms: 500\n  ducking-level: 35\n"));
        assertEquals(500, settings.fadeDurationMs());
        assertEquals(25, settings.fadeSteps());
        assertEquals(0.35f, settings.duckingFloor(), 1e-6);
    }

    @Test
    void zeroFadeIsAnImmediateSwitch() {
        assertEquals(1, new AudioSettings(0, 0).fadeSteps());
        assertEquals(1, new AudioSettings(19, 0).fadeSteps(), "less than one frame rounds up to one");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> new AudioSettings(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AudioSettings(200, 101));
        assertThrows(IllegalArgumentException.class, () -> new AudioSettings(200, -5));
    }
}
