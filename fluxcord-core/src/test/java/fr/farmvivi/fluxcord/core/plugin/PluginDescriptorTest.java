package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorTest {

    private static InputStream yaml(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesAFullDescriptor() throws Exception {
        PluginDescriptor d = PluginDescriptor.fromYaml(yaml("""
                id: music-plugin
                name: Fluxcord Plugin - Music
                version: 3.0.0-SNAPSHOT
                main: fr.farmvivi.fluxcord.plugins.music.MusicPlugin
                description: Plays music
                authors: [ FarmVivi, Someone ]
                dependencies: [ audio-core ]
                soft-dependencies: [ announcer ]
                """));

        assertEquals("music-plugin", d.id());
        assertEquals("Fluxcord Plugin - Music", d.name());
        assertEquals("3.0.0-SNAPSHOT", d.version());
        assertEquals("fr.farmvivi.fluxcord.plugins.music.MusicPlugin", d.main());
        assertEquals("Plays music", d.description());
        assertEquals(List.of("FarmVivi", "Someone"), d.authors());
        assertEquals(List.of("audio-core"), d.dependencies());
        assertEquals(List.of("announcer"), d.softDependencies());
    }

    @Test
    void optionalFieldsDefaultToEmpty() throws Exception {
        PluginDescriptor d = PluginDescriptor.fromYaml(yaml("""
                id: p
                name: P
                version: 1
                main: com.example.P
                """));

        assertEquals("", d.description());
        assertTrue(d.authors().isEmpty());
        assertTrue(d.dependencies().isEmpty());
        assertTrue(d.softDependencies().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> d.dependencies().add("x"), "lists are immutable");
    }

    @Test
    void numericVersionIsReadAsString() throws Exception {
        PluginDescriptor d = PluginDescriptor.fromYaml(yaml("id: p\nname: P\nversion: 1.2\nmain: c.P\n"));
        assertEquals("1.2", d.version());
    }

    @Test
    void eachRequiredFieldIsValidated() {
        for (String missing : List.of("id", "name", "version", "main")) {
            StringBuilder content = new StringBuilder();
            for (String field : List.of("id", "name", "version", "main")) {
                if (!field.equals(missing)) {
                    content.append(field).append(": value\n");
                }
            }
            ConfigurationException e = assertThrows(ConfigurationException.class,
                    () -> PluginDescriptor.fromYaml(yaml(content.toString())), "missing " + missing);
            assertTrue(e.getMessage().contains("Failed to parse plugin.yml"), e.getMessage());
        }
    }

    @Test
    void emptyRequiredFieldIsRejected() {
        assertThrows(ConfigurationException.class,
                () -> PluginDescriptor.fromYaml(yaml("id: \"\"\nname: P\nversion: 1\nmain: c.P\n")));
    }

    @Test
    void scalarWhereAListIsExpectedIsIgnored() throws Exception {
        // Characterization: a scalar 'dependencies' is silently treated as no dependency.
        PluginDescriptor d = PluginDescriptor.fromYaml(yaml("id: p\nname: P\nversion: 1\nmain: c.P\ndependencies: other\n"));
        assertTrue(d.dependencies().isEmpty());
    }

    @Test
    void invalidYamlIsReportedAsConfigurationException() {
        assertThrows(ConfigurationException.class, () -> PluginDescriptor.fromYaml(yaml("- just\n- a list\n")));
        assertThrows(ConfigurationException.class, () -> PluginDescriptor.fromYaml(yaml("id: [unclosed\n")));
    }
}
