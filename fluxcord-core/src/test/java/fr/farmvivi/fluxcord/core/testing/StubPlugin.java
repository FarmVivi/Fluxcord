package fr.farmvivi.fluxcord.core.testing;

import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;

/**
 * A plugin that exists only to be identified.
 *
 * <p>The core's services key almost everything by plugin — commands, permissions, listeners, storage
 * namespaces — so their tests need a {@link Plugin} without wanting one to do anything. Eight test classes
 * had each written this same stub; this is that class, once.
 *
 * <p>The lifecycle hooks are empty on purpose: a test that needs to observe a phase uses
 * {@code PluginCalls} and a real plugin jar instead (see {@code PluginManagerTest}).
 */
public class StubPlugin implements Plugin {

    private final String id;
    private PluginLifecycle lifecycle = PluginLifecycle.LOADED;

    public StubPlugin(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return id;
    }

    @Override
    public String getVersion() {
        return "1";
    }

    @Override
    public void onLoad(PluginContext context) {
        // Nothing to load: this stub holds no services.
    }

    @Override
    public void onEnable() {
        // Nothing to enable.
    }

    @Override
    public void onDisable() {
        // Nothing to release.
    }

    @Override
    public PluginLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void setLifecycle(PluginLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }
}
