package fr.farmvivi.fluxcord.api.event;

/**
 * Annotation to mark methods as event handlers.
 */
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
public @interface EventHandler {
    /**
     * The priority of the event handler.
     * Handlers run from {@link EventPriority#LOWEST} to {@link EventPriority#MONITOR}: a higher priority
     * runs later and therefore has the final say (e.g. on cancellation); {@code MONITOR} should only observe.
     *
     * @return the priority
     */
    EventPriority priority() default EventPriority.NORMAL;

    /**
     * When {@code true}, this handler is skipped once the event has been cancelled (same meaning as in
     * Bukkit). The default {@code false} means the handler is always called and must check
     * {@link Cancellable#isCancelled()} itself if it cares.
     *
     * @return true to skip the handler for cancelled events
     */
    boolean ignoreCancelled() default false;
}