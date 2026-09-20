package fr.farmvivi.fluxcord.core.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared call log for fixture plugins loaded through a {@code PluginClassLoader}. It lives in a core package
 * on purpose: core packages are parent-first, so the plugin copy loaded by the child class loader and the
 * test see the same class (and the same static list). Fixture classes outside core would get their own copy.
 */
public final class PluginCalls {
    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

    private PluginCalls() { }

    public static void record(String pluginId, String phase) {
        CALLS.add(pluginId + ":" + phase);
    }

    public static List<String> all() {
        return List.copyOf(CALLS);
    }

    public static List<String> of(String pluginId) {
        return CALLS.stream().filter(c -> c.startsWith(pluginId + ":")).map(c -> c.substring(pluginId.length() + 1)).toList();
    }

    public static void reset() {
        CALLS.clear();
    }
}
