package com.example.fixture;

import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.core.testing.PluginCalls;

/**
 * Minimal real plugin whose bytecode is packed into test jars. Records every lifecycle phase through
 * {@link PluginCalls}; phases named in the system property {@code fixture.fail} (comma-separated) throw.
 */
public class FixturePlugin extends AbstractPlugin {

    private void phase(String name) {
        PluginCalls.record(getId(), name);
        String fail = System.getProperty("fixture.fail", "");
        for (String f : fail.split(",")) {
            if (f.equals(getId() + ":" + name)) {
                throw new IllegalStateException("fixture failure in " + name);
            }
        }
    }

    @Override public void onLoad(fr.farmvivi.fluxcord.api.plugin.PluginContext context) { super.onLoad(context); phase("onLoad"); }
    @Override public void onPreEnable() { phase("onPreEnable"); }
    @Override public void onEnable() { phase("onEnable"); }
    @Override public void onPostEnable() { phase("onPostEnable"); }
    @Override public void onPreDisable() { phase("onPreDisable"); }
    @Override public void onDisable() { super.onDisable(); phase("onDisable"); }
    @Override public void onPostDisable() { phase("onPostDisable"); }
}
