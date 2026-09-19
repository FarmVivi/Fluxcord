package fr.farmvivi.fluxcord.core.plugin;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for the plugin load-order resolution.
 * The load order must list every plugin after all of its (hard and soft) dependencies.
 */
class DependencyResolverTest {

    private static PluginDescriptor descriptor(String id, List<String> deps, List<String> softDeps) {
        return new PluginDescriptor(id, id, "com.example." + id, "1.0", "", List.of(), deps, softDeps);
    }

    private static Map<String, PluginDescriptor> plugins(PluginDescriptor... descriptors) {
        Map<String, PluginDescriptor> map = new LinkedHashMap<>();
        for (PluginDescriptor d : descriptors) {
            map.put(d.id(), d);
        }
        return map;
    }

    private static void assertBefore(List<String> order, String dependency, String dependant) {
        int depIndex = order.indexOf(dependency);
        int dependantIndex = order.indexOf(dependant);
        assertTrue(depIndex >= 0 && dependantIndex >= 0, "both plugins must be in the order: " + order);
        assertTrue(depIndex < dependantIndex,
                dependency + " must be loaded before " + dependant + " but order was " + order);
    }

    @Test
    void hardDependencyIsLoadedBeforeDependant() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("music", List.of("audio-core"), List.of()),
                descriptor("audio-core", List.of(), List.of())));

        List<String> order = resolver.resolve();

        assertEquals(2, order.size());
        assertBefore(order, "audio-core", "music");
        assertTrue(resolver.getMissingDependencies().isEmpty());
        assertTrue(resolver.getCircularDependencies().isEmpty());
    }

    @Test
    void softDependencyIsLoadedBeforeDependantWhenPresent() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("announcer", List.of(), List.of("music")),
                descriptor("music", List.of(), List.of())));

        List<String> order = resolver.resolve();

        assertBefore(order, "music", "announcer");
    }

    @Test
    void missingSoftDependencyIsIgnored() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("announcer", List.of(), List.of("not-installed"))));

        List<String> order = resolver.resolve();

        assertEquals(List.of("announcer"), order);
        assertTrue(resolver.getMissingDependencies().isEmpty());
    }

    @Test
    void missingHardDependencyIsReportedForTheDependant() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("music", List.of("not-installed"), List.of()),
                descriptor("other", List.of(), List.of())));

        resolver.resolve();

        assertEquals(java.util.Set.of("music"), resolver.getMissingDependencies());
    }

    @Test
    void transitiveChainIsOrdered() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("c", List.of("b"), List.of()),
                descriptor("a", List.of(), List.of()),
                descriptor("b", List.of("a"), List.of())));

        List<String> order = resolver.resolve();

        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void orderIsDeterministicForIndependentPlugins() {
        // Independent plugins should come out in a stable (alphabetical) order, whatever the map order.
        DependencyResolver first = new DependencyResolver(plugins(
                descriptor("zeta", List.of(), List.of()),
                descriptor("alpha", List.of(), List.of()),
                descriptor("mid", List.of(), List.of())));
        DependencyResolver second = new DependencyResolver(plugins(
                descriptor("mid", List.of(), List.of()),
                descriptor("zeta", List.of(), List.of()),
                descriptor("alpha", List.of(), List.of())));

        assertEquals(List.of("alpha", "mid", "zeta"), first.resolve());
        assertEquals(first.resolve(), second.resolve());
    }

    @Test
    void circularDependencyIsReported() {
        DependencyResolver resolver = new DependencyResolver(plugins(
                descriptor("a", List.of("b"), List.of()),
                descriptor("b", List.of("a"), List.of()),
                descriptor("standalone", List.of(), List.of())));

        List<String> order = resolver.resolve();

        assertEquals(java.util.Set.of("a", "b"), resolver.getCircularDependencies());
        assertTrue(order.contains("standalone"), "plugins outside the cycle must still be loadable");
    }
}
