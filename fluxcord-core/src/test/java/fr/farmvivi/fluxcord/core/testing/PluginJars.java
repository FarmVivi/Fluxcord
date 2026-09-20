package fr.farmvivi.fluxcord.core.testing;

import com.example.fixture.FixturePlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Builds real plugin jars in a temporary directory for engine tests: bytecode is copied from classes already on
 * the test classpath, text resources are written as given.
 */
public final class PluginJars {

    private PluginJars() { }

    /** A jar with the given classes and text resources (path → content). */
    public static File build(Path dir, String fileName, List<Class<?>> classes, Map<String, String> resources) throws IOException {
        File jar = dir.resolve(fileName).toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            for (Class<?> c : classes) {
                String path = c.getName().replace('.', '/') + ".class";
                try (InputStream in = c.getClassLoader().getResourceAsStream(path)) {
                    assertNotNull(in, "bytecode of " + c + " must be readable");
                    out.putNextEntry(new JarEntry(path));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
            for (Map.Entry<String, String> r : resources.entrySet()) {
                out.putNextEntry(new JarEntry(r.getKey()));
                out.write(r.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * A loadable plugin jar: {@code plugin.yml} for the given id (main = {@link FixturePlugin}) plus the
     * fixture's bytecode. {@code extraYaml} is appended verbatim (dependencies, soft-dependencies...).
     */
    public static File plugin(Path dir, String id, String extraYaml) throws IOException {
        String yml = "id: " + id + "\nname: " + id + " plugin\nversion: 1.0\nmain: " + FixturePlugin.class.getName() + "\n" + extraYaml;
        return build(dir, id + ".jar", List.of(FixturePlugin.class), Map.of("plugin.yml", yml));
    }

    public static File plugin(Path dir, String id) throws IOException {
        return plugin(dir, id, "");
    }
}
