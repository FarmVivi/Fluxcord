package fr.farmvivi.fluxcord.api.event;

import fr.farmvivi.fluxcord.api.plugin.Plugin;

import java.util.Set;

/**
 * Interface for the event management system.
 * This is used to register event listeners, fire events,
 * and manage event types.
 */
public interface EventManager extends EventRegistry {
    /**
     * Registers an event listener.
     * The listener will be unregistered automatically when the plugin is disabled.
     *
     * @param listener the listener to register
     * @param plugin   the plugin that owns this listener
     */
    void registerListener(Object listener, Plugin plugin);

    /**
     * Unregisters an event listener.
     *
     * @param listener the listener to unregister
     * @return true if the listener was unregistered, false otherwise
     */
    boolean unregisterListener(Object listener);

    /**
     * Unregisters all event listeners from a specific plugin.
     *
     * @param plugin the plugin whose listeners should be unregistered
     * @return the number of listeners unregistered
     */
    int unregisterAll(Plugin plugin);

    /**
     * Fires an event synchronously on the calling thread. Handlers run by {@link EventPriority} from
     * {@code LOWEST} to {@code MONITOR}; within a priority, handlers of the concrete event class run before
     * handlers of its supertypes (a handler on a parent event type receives every subclass event).
     * Exceptions thrown by handlers are logged and do not stop the dispatch.
     *
     * @param event the event to fire
     * @return the event that was fired (may have been modified by listeners)
     */
    <T extends Event> T fireEvent(T event);

    /**
     * Fires an event asynchronously.
     *
     * @param event the event to fire
     */
    void fireEventAsync(Event event);

    /**
     * Gets a set of all registered listeners for a specific plugin.
     *
     * @param plugin the plugin
     * @return a set of registered listeners for the plugin
     */
    Set<Object> getRegisteredListeners(Plugin plugin);

    /**
     * Gets the plugin that registered a specific listener.
     *
     * @param listener the listener
     * @return the plugin that registered the listener, or null if not found
     */
    Plugin getOwningPlugin(Object listener);

    /**
     * Checks if a specific listener is registered.
     *
     * @param listener the listener
     * @return true if the listener is registered, false otherwise
     */
    boolean isListenerRegistered(Object listener);

    /**
     * Whether firing an event of this class would reach at least one handler (registered for the class
     * itself or one of its supertypes). Lets hot paths skip building high-frequency events (e.g. one per
     * audio frame) when nobody listens.
     *
     * @param eventType the concrete event class
     * @return true if at least one handler would receive it; implementations that cannot tell return true
     */
    default boolean hasListeners(Class<? extends Event> eventType) {
        return true;
    }
}