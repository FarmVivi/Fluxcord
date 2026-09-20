package fr.farmvivi.fluxcord.core.event;

import fr.farmvivi.fluxcord.api.event.*;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple implementation of EventManager with explicit plugin management.
 */
public class SimpleEventManager implements EventManager {
    private static final Logger logger = LoggerFactory.getLogger(SimpleEventManager.class);

    // Event type registry
    private final Map<Class<? extends Event>, EventTypeInfo> eventTypeRegistry = new ConcurrentHashMap<>();
    private final Map<Plugin, Set<Class<? extends Event>>> pluginEventTypes = new ConcurrentHashMap<>();

    // Map: Event Type -> Priority -> List of RegisteredListeners.
    // Listeners are registered on the main thread while events are fired from any thread (storage,
    // async executor), so every level is safe for concurrent read/write; the lists are copy-on-write
    // so fireEvent iterates a snapshot without copying.
    private final Map<Class<? extends Event>, Map<EventPriority, List<RegisteredListener>>> eventTypeMap = new ConcurrentHashMap<>();

    // Concrete event class -> itself + every Event supertype, nearest first (see eventHierarchy)
    private final Map<Class<? extends Event>, List<Class<? extends Event>>> eventHierarchyCache = new ConcurrentHashMap<>();

    // Map: Plugin -> Set of Listeners owned by that plugin
    private final Map<Plugin, Set<Object>> pluginListenersMap = new ConcurrentHashMap<>();

    // Map: Listener -> RegisteredListener (for fast lookup)
    private final Map<Object, Set<RegisteredListener>> listenerHandlersMap = new ConcurrentHashMap<>();

    // Map: Listener -> Plugin (for ownership tracking)
    private final Map<Object, Plugin> listenerOwnerMap = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicInteger totalRegisteredHandlers = new AtomicInteger(0);

    // Thread pool for async event handling
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "EventManager-AsyncWorker");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public boolean registerEventType(Class<? extends Event> eventType, Plugin plugin, String description) {
        if (eventType == null) {
            throw new IllegalArgumentException("Event type cannot be null");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        // Check if already registered
        if (eventTypeRegistry.containsKey(eventType)) {
            EventTypeInfo existingInfo = eventTypeRegistry.get(eventType);
            logger.warn("Event type {} is already registered by plugin {}",
                    eventType.getName(), existingInfo.getPlugin().getName());
            return false;
        }

        // Create event type info
        EventTypeInfo info = new EventTypeInfo(eventType, plugin, description);
        eventTypeRegistry.put(eventType, info);

        // Add to plugin event types
        pluginEventTypes.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet())
                .add(eventType);

        logger.debug("Registered event type {} owned by plugin {}",
                eventType.getName(), plugin.getName());
        return true;
    }

    @Override
    public boolean registerEventType(Class<? extends Event> eventType, Plugin plugin) {
        return registerEventType(eventType, plugin, "");
    }

    @Override
    public int unregisterEventTypes(Plugin plugin) {
        if (plugin == null) {
            return 0;
        }

        Set<Class<? extends Event>> eventTypes = pluginEventTypes.get(plugin);
        if (eventTypes == null || eventTypes.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Class<? extends Event> eventType : new HashSet<>(eventTypes)) {
            eventTypeRegistry.remove(eventType);
            count++;
        }

        pluginEventTypes.remove(plugin);

        logger.debug("Unregistered {} event types for plugin {}",
                count, plugin.getName());
        return count;
    }

    @Override
    public EventTypeInfo getEventTypeInfo(Class<? extends Event> eventType) {
        return eventTypeRegistry.get(eventType);
    }

    @Override
    public Set<Class<? extends Event>> getRegisteredEventTypes() {
        return new HashSet<>(eventTypeRegistry.keySet());
    }

    @Override
    public Set<Class<? extends Event>> getPluginEventTypes(Plugin plugin) {
        Set<Class<? extends Event>> types = pluginEventTypes.get(plugin);
        return types != null ? Collections.unmodifiableSet(types) : Collections.emptySet();
    }

    @Override
    public Plugin getEventTypeOwner(Class<? extends Event> eventType) {
        EventTypeInfo info = eventTypeRegistry.get(eventType);
        return info != null ? info.getPlugin() : null;
    }

    @Override
    public void registerListener(Object listener, Plugin plugin) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }

        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        // Check if listener is already registered
        if (listenerOwnerMap.containsKey(listener)) {
            logger.warn("Listener {} is already registered by plugin {}",
                    listener.getClass().getName(), listenerOwnerMap.get(listener).getClass().getName());
            return;
        }

        // Initialize the listener handlers set if not exists
        Set<RegisteredListener> registeredHandlers = new HashSet<>();

        // Find methods with @EventHandler annotation
        for (Method method : listener.getClass().getMethods()) {
            if (!method.isAnnotationPresent(EventHandler.class)) {
                continue;
            }

            // Validate method signature
            if (method.getParameterCount() != 1) {
                logger.warn("Method {} in {} has @EventHandler but does not have exactly one parameter",
                        method.getName(), listener.getClass().getName());
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            if (!Event.class.isAssignableFrom(parameterType)) {
                logger.warn("Method {} in {} has @EventHandler but its parameter is not an Event",
                        method.getName(), listener.getClass().getName());
                continue;
            }

            Class<? extends Event> eventType = (Class<? extends Event>) parameterType;

            // Get the event handler annotation
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            EventPriority priority = annotation.priority();
            boolean ignoreCancelled = annotation.ignoreCancelled();

            // Create a registered listener
            RegisteredListener registeredListener = new RegisteredListener(
                    listener, method, eventType, priority, ignoreCancelled, plugin);

            // Add to the event type map
            eventTypeMap.computeIfAbsent(eventType, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(priority, k -> new CopyOnWriteArrayList<>())
                    .add(registeredListener);

            // Add to the registered handlers
            registeredHandlers.add(registeredListener);

            // Increment the counter
            totalRegisteredHandlers.incrementAndGet();

            logger.debug("Registered event handler: {}#{} in {} for event {} (owned by {})",
                    method.getName(),
                    priority,
                    listener.getClass().getSimpleName(),
                    eventType.getSimpleName(),
                    plugin.getClass().getSimpleName());

            // If the event type is not yet registered, register it automatically
            if (!eventTypeRegistry.containsKey(eventType)) {
                // Check if it's a core event or from another plugin
                if (eventType.getPackage().getName().startsWith(plugin.getClass().getPackage().getName())) {
                    // It's from this plugin, register it
                    registerEventType(eventType, plugin, "Auto-registered event");
                }
            }
        }

        // If any handlers were registered, track the listener
        if (!registeredHandlers.isEmpty()) {
            // Store the registered handlers
            listenerHandlersMap.put(listener, registeredHandlers);

            // Track ownership
            listenerOwnerMap.put(listener, plugin);

            // Add to the plugin listeners map
            pluginListenersMap.computeIfAbsent(plugin, k -> ConcurrentHashMap.newKeySet()).add(listener);

            logger.info("Registered listener {} with {} event handlers (owned by {})",
                    listener.getClass().getSimpleName(),
                    registeredHandlers.size(),
                    plugin.getClass().getSimpleName());
        } else {
            logger.warn("Listener {} has no @EventHandler methods", listener.getClass().getName());
        }
    }

    @Override
    public boolean unregisterListener(Object listener) {
        if (listener == null) {
            return false;
        }

        // Check if listener is registered
        Set<RegisteredListener> handlers = listenerHandlersMap.get(listener);
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }

        // Get the owning plugin
        Object plugin = listenerOwnerMap.get(listener);

        // Remove all registered handlers
        for (RegisteredListener handler : handlers) {
            // Get the event map
            Map<EventPriority, List<RegisteredListener>> priorityMap = eventTypeMap.get(handler.getEventType());
            if (priorityMap != null) {
                // Get the priority list
                List<RegisteredListener> priorityList = priorityMap.get(handler.getPriority());
                if (priorityList != null) {
                    // Remove the handler
                    priorityList.remove(handler);

                    // Decrement the counter
                    totalRegisteredHandlers.decrementAndGet();

                    // Clean up empty lists
                    if (priorityList.isEmpty()) {
                        priorityMap.remove(handler.getPriority());
                    }

                    // Clean up empty maps
                    if (priorityMap.isEmpty()) {
                        eventTypeMap.remove(handler.getEventType());
                    }
                }
            }
        }

        // Remove from the listener handlers map
        listenerHandlersMap.remove(listener);

        // Remove ownership
        listenerOwnerMap.remove(listener);

        // Remove from the plugin listeners map
        if (plugin != null) {
            Set<Object> pluginListeners = pluginListenersMap.get(plugin);
            if (pluginListeners != null) {
                pluginListeners.remove(listener);

                // Clean up empty sets
                if (pluginListeners.isEmpty()) {
                    pluginListenersMap.remove(plugin);
                }
            }
        }

        logger.info("Unregistered listener {} with {} handlers",
                listener.getClass().getSimpleName(), handlers.size());

        return true;
    }

    @Override
    public int unregisterAll(Plugin plugin) {
        if (plugin == null) {
            return 0;
        }

        // Get the listeners for this plugin
        Set<Object> listeners = pluginListenersMap.get(plugin);
        if (listeners == null || listeners.isEmpty()) {
            return 0;
        }

        // Make a copy to avoid concurrent modification
        Set<Object> listenersCopy = new HashSet<>(listeners);

        // Unregister each listener
        int count = 0;
        for (Object listener : listenersCopy) {
            if (unregisterListener(listener)) {
                count++;
            }
        }

        // Make sure the plugin is removed from the map
        pluginListenersMap.remove(plugin);

        logger.info("Unregistered all {} listeners for plugin {}", count, plugin.getClass().getSimpleName());

        return count;
    }

    @Override
    public <T extends Event> T fireEvent(T event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }

        Class<? extends Event> eventType = event.getClass();

        // Handlers registered for the concrete class first, then for each supertype (class or interface)
        // that extends Event, so a handler on PluginEvent also receives PluginEnabledEvent.
        List<Map<EventPriority, List<RegisteredListener>>> priorityMaps = new ArrayList<>();
        for (Class<? extends Event> type : eventHierarchy(eventType)) {
            Map<EventPriority, List<RegisteredListener>> priorityMap = eventTypeMap.get(type);
            if (priorityMap != null && !priorityMap.isEmpty()) {
                priorityMaps.add(priorityMap);
            }
        }
        if (priorityMaps.isEmpty()) {
            return event;
        }

        // Check if event is cancellable
        boolean isCancelled = event instanceof Cancellable && ((Cancellable) event).isCancelled();

        // Call handlers in order of priority
        for (EventPriority priority : EventPriority.values()) {
            for (Map<EventPriority, List<RegisteredListener>> priorityMap : priorityMaps) {
                List<RegisteredListener> handlers = priorityMap.get(priority);
                if (handlers == null || handlers.isEmpty()) {
                    continue;
                }

                // Copy-on-write list: the iterator is a snapshot, registrations during dispatch are not seen
                for (RegisteredListener handler : handlers) {
                    // Bukkit semantics: ignoreCancelled = true means "don't call me once the event is cancelled"
                    if (isCancelled && handler.isIgnoreCancelled()) {
                        continue;
                    }

                    try {
                        // Call the handler
                        handler.callEvent(event);

                        // Update cancellation status if changed
                        if (event instanceof Cancellable) {
                            isCancelled = ((Cancellable) event).isCancelled();
                        }
                    } catch (Throwable t) {
                        logger.error("Error dispatching event {} to listener {} (owned by {})",
                                eventType.getSimpleName(),
                                handler.getListener().getClass().getSimpleName(),
                                handler.getPlugin().getClass().getSimpleName(),
                                t);
                    }
                }
            }
        }

        return event;
    }

    /**
     * The concrete event class followed by every superclass and interface that extends {@link Event},
     * nearest first, without duplicates. Computed once per concrete class.
     */
    @SuppressWarnings("unchecked")
    private List<Class<? extends Event>> eventHierarchy(Class<? extends Event> eventType) {
        return eventHierarchyCache.computeIfAbsent(eventType, type -> {
            List<Class<? extends Event>> result = new ArrayList<>();
            Deque<Class<?>> queue = new ArrayDeque<>();
            queue.add(type);
            while (!queue.isEmpty()) {
                Class<?> current = queue.poll();
                if (!Event.class.isAssignableFrom(current) || result.contains(current)) {
                    continue;
                }
                result.add((Class<? extends Event>) current);
                if (current.getSuperclass() != null) {
                    queue.add(current.getSuperclass());
                }
                queue.addAll(Arrays.asList(current.getInterfaces()));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public void fireEventAsync(Event event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }

        asyncExecutor.submit(() -> {
            try {
                fireEvent(event);
            } catch (Throwable t) {
                logger.error("Error in async event dispatch for {}", event.getClass().getSimpleName(), t);
            }
        });
    }

    @Override
    public Set<Object> getRegisteredListeners(Plugin plugin) {
        if (plugin == null) {
            return Collections.emptySet();
        }

        Set<Object> listeners = pluginListenersMap.get(plugin);
        if (listeners == null) {
            return Collections.emptySet();
        }

        return new HashSet<>(listeners);
    }

    @Override
    public Plugin getOwningPlugin(Object listener) {
        if (listener == null) {
            return null;
        }

        return listenerOwnerMap.get(listener);
    }

    @Override
    public boolean isListenerRegistered(Object listener) {
        if (listener == null) {
            return false;
        }

        return listenerOwnerMap.containsKey(listener);
    }

    /**
     * Gets the total number of registered event handlers.
     *
     * @return the total number of registered event handlers
     */
    public int getTotalHandlerCount() {
        return totalRegisteredHandlers.get();
    }

    /**
     * Gets the number of registered event handlers for a specific event type.
     *
     * @param eventType the event type
     * @return the number of registered event handlers for the event type
     */
    public int getHandlerCount(Class<? extends Event> eventType) {
        if (eventType == null) {
            return 0;
        }

        Map<EventPriority, List<RegisteredListener>> priorityMap = eventTypeMap.get(eventType);
        if (priorityMap == null) {
            return 0;
        }

        int count = 0;
        for (List<RegisteredListener> handlers : priorityMap.values()) {
            count += handlers.size();
        }

        return count;
    }

    /**
     * Gets all event types and handlers for a specific event type.
     * This can be used to implement event bridging between plugins.
     *
     * @param eventType the event type
     * @return a map of priorities to handlers for the event type
     */
    public Map<EventPriority, List<RegisteredListener>> getEventHandlers(Class<? extends Event> eventType) {
        Map<EventPriority, List<RegisteredListener>> handlers = eventTypeMap.get(eventType);
        if (handlers == null) {
            return Collections.emptyMap();
        }

        Map<EventPriority, List<RegisteredListener>> result = new EnumMap<>(EventPriority.class);
        for (Map.Entry<EventPriority, List<RegisteredListener>> entry : handlers.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return result;
    }

    /**
     * Gets the number of registered event handlers for each event type.
     *
     * @return a map of event types to handler counts
     */
    public Map<Class<? extends Event>, Integer> getHandlerCounts() {
        Map<Class<? extends Event>, Integer> result = new HashMap<>();

        for (Map.Entry<Class<? extends Event>, Map<EventPriority, List<RegisteredListener>>> entry : eventTypeMap.entrySet()) {
            int count = 0;
            for (List<RegisteredListener> handlers : entry.getValue().values()) {
                count += handlers.size();
            }
            result.put(entry.getKey(), count);
        }

        return result;
    }

    /**
     * Shuts down the event manager, canceling any pending async events.
     */
    public void shutdown() {
        asyncExecutor.shutdownNow();
    }

    /**
     * Represents a registered event listener.
     */
    private static class RegisteredListener {
        private final Object listener;
        private final Method method;
        private final Class<? extends Event> eventType;
        private final EventPriority priority;
        private final boolean ignoreCancelled;
        private final Object plugin;

        /**
         * Creates a new registered event listener.
         *
         * @param listener        the listener
         * @param method          the method
         * @param eventType       the event type
         * @param priority        the priority
         * @param ignoreCancelled whether to ignore cancelled events
         * @param plugin          the plugin that registered the listener
         */
        public RegisteredListener(Object listener, Method method, Class<? extends Event> eventType,
                                  EventPriority priority, boolean ignoreCancelled, Object plugin) {
            this.listener = listener;
            this.method = method;
            this.eventType = eventType;
            this.priority = priority;
            this.ignoreCancelled = ignoreCancelled;
            this.plugin = plugin;

            // Make the method accessible for faster invocation
            method.setAccessible(true);
        }

        /**
         * Calls the event listener.
         *
         * @param event the event
         * @throws Exception if there's an error calling the listener
         */
        public void callEvent(Event event) throws Exception {
            if (eventType.isAssignableFrom(event.getClass())) {
                method.invoke(listener, event);
            }
        }

        /**
         * Gets the listener.
         *
         * @return the listener
         */
        public Object getListener() {
            return listener;
        }

        /**
         * Gets the method.
         *
         * @return the method
         */
        public Method getMethod() {
            return method;
        }

        /**
         * Gets the event type.
         *
         * @return the event type
         */
        public Class<? extends Event> getEventType() {
            return eventType;
        }

        /**
         * Gets the priority.
         *
         * @return the priority
         */
        public EventPriority getPriority() {
            return priority;
        }

        /**
         * Checks if the listener ignores cancelled events.
         *
         * @return true if the listener ignores cancelled events
         */
        public boolean isIgnoreCancelled() {
            return ignoreCancelled;
        }

        /**
         * Gets the plugin that registered the listener.
         *
         * @return the plugin
         */
        public Object getPlugin() {
            return plugin;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RegisteredListener that = (RegisteredListener) o;
            return listener.equals(that.listener) && method.equals(that.method);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener, method);
        }
    }
}