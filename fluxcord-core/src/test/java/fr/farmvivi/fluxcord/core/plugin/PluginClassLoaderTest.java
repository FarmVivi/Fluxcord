package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import com.example.fixture.SamplePluginClass;
import fr.farmvivi.fluxcord.core.util.DiscordColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Child-first isolation rules of the plugin class loader, checked against a real jar built on the fly:
 * a plugin's own classes and resources win over the parent, except the core/shared packages that must
 * stay unique in the JVM.
 */
class PluginClassLoaderTest {

    private static final PluginDescriptor DESCRIPTOR =
            new PluginDescriptor("test", "Test", "x.Main", "1", "", List.of(), List.of(), List.of());

    /** Builds a jar containing the given classes (bytecode copied from the test classpath) and text resources. */
    private static File buildJar(Path dir, List<Class<?>> classes, Map<String, String> resources) throws IOException {
        File jar = dir.resolve("plugin.jar").toFile();
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

    private static PluginClassLoader loader(File jar) throws IOException {
        return new PluginClassLoader(new URL[]{jar.toURI().toURL()}, PluginClassLoaderTest.class.getClassLoader(), DESCRIPTOR);
    }

    @Test
    void pluginClassesAreLoadedChildFirst(@TempDir Path dir) throws Exception {
        File jar = buildJar(dir, List.of(SamplePluginClass.class), Map.of());
        try (PluginClassLoader loader = loader(jar)) {
            Class<?> loaded = loader.loadClass(SamplePluginClass.class.getName());

            assertSame(loader, loaded.getClassLoader(), "the jar copy must win over the parent's copy");
            assertNotSame(SamplePluginClass.class, loaded);
            assertEquals("sample", loaded.getField("MARKER").get(null));
            assertSame(loaded, loader.loadClass(SamplePluginClass.class.getName()), "loaded once, then cached");
        }
    }

    @Test
    void classesAbsentFromTheJarComeFromTheParent(@TempDir Path dir) throws Exception {
        File jar = buildJar(dir, List.of(), Map.of());
        try (PluginClassLoader loader = loader(jar)) {
            assertSame(SamplePluginClass.class, loader.loadClass(SamplePluginClass.class.getName()));
            assertSame(String.class, loader.loadClass("java.lang.String"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("does.not.Exist"));
        }
    }

    @Test
    void corePackagesAreAlwaysParentFirstEvenWhenBundled(@TempDir Path dir) throws Exception {
        File jar = buildJar(dir, List.of(DiscordColor.class), Map.of());
        try (PluginClassLoader loader = loader(jar)) {
            assertSame(DiscordColor.class, loader.loadClass(DiscordColor.class.getName()),
                    "fr.farmvivi.fluxcord.core.* must never be duplicated per plugin");
        }
    }

    @Test
    void apiPackageIsParentFirstEvenWhenBundled(@TempDir Path dir) throws Exception {
        // A plugin jar that (wrongly) bundles fluxcord-api must still share the core's api classes,
        // otherwise Plugin/PluginContext types differ between core and plugin (ClassCastException at load).
        File jar = buildJar(dir, List.of(PluginLifecycle.class), Map.of());
        try (PluginClassLoader loader = loader(jar)) {
            assertSame(PluginLifecycle.class, loader.loadClass(PluginLifecycle.class.getName()),
                    "fr.farmvivi.fluxcord.api.* must come from the core class loader");
        }
    }

    @Test
    void resourcesAreChildFirst(@TempDir Path dir) throws Exception {
        // config.yml also exists on the core classpath: the plugin must get its own.
        File jar = buildJar(dir, List.of(), Map.of("config.yml", "plugin: true\n", "only-in-plugin.txt", "x"));
        try (PluginClassLoader loader = loader(jar)) {
            URL config = loader.getResource("config.yml");
            assertNotNull(config);
            assertTrue(config.toString().contains("plugin.jar"), config.toString());
            try (InputStream in = loader.getResourceAsStream("config.yml")) {
                assertEquals("plugin: true\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            assertNotNull(loader.getResource("only-in-plugin.txt"));
            assertNull(loader.getResource("definitely-missing.txt"));

            List<URL> all = java.util.Collections.list(loader.getResources("config.yml"));
            assertTrue(all.size() >= 2, "child and parent resources are both listed: " + all);
            assertTrue(all.get(0).toString().contains("plugin.jar"), "child resource first");
        }
    }

    @Test
    void descriptorIsExposed(@TempDir Path dir) throws Exception {
        try (PluginClassLoader loader = loader(buildJar(dir, List.of(), Map.of()))) {
            assertSame(DESCRIPTOR, loader.getDescriptor());
        }
    }
}
