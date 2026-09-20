package com.example.plugin.events;

import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.event.EventPriority;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.plugin.events.PluginEnableEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Example listener showing the two event buses:
 * <ul>
 *   <li>Discord events (JDA): override {@link ListenerAdapter} methods and register with
 *       {@code addDiscordListeners(listener)} — {@code @EventHandler} never receives JDA events;</li>
 *   <li>Fluxcord events (plugins, storage, permissions, i18n, audio, commands): {@code @EventHandler}
 *       methods, registered with {@code eventManager.registerListener(listener, plugin)}.</li>
 * </ul>
 * The same object can do both, as here.
 */
public class ExampleEventListener extends ListenerAdapter {

    private final AbstractPlugin plugin;

    public ExampleEventListener(AbstractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Discord message event (JDA bus): a {@link ListenerAdapter} override.
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Skip bot messages
        if (event.getAuthor().isBot()) {
            return;
        }

        // Check if event handling is enabled
        if (!plugin.getConfiguration().getBoolean("events.enabled", true)) {
            return;
        }

        // Example: Log messages in debug mode
        if (plugin.getConfiguration().getBoolean("debug.log_messages", false)) {
            plugin.getLogger().debug("Message received from {}: {}",
                    event.getAuthor().getAsTag(),
                    event.getMessage().getContentRaw());
        }

        // Example: Respond to mentions (if enabled)
        if (event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser()) &&
                plugin.getConfiguration().getBoolean("features.respond_to_mentions", false)) {

            String response = plugin.getLanguage()
                    .getString("messages.mention_response", event.getAuthor().getAsMention());

            event.getChannel().sendMessage(response).queue();
        }
    }

    /**
     * Fluxcord event (internal bus): an {@code @EventHandler} method.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPluginEvent(PluginEnableEvent event) {
        // React to other plugins being enabled
        if (plugin.getConfiguration().getBoolean("debug.log_plugin_events", false)) {
            plugin.getLogger().info("Plugin enabled: {}", event.getPlugin().getName());
        }
    }
}