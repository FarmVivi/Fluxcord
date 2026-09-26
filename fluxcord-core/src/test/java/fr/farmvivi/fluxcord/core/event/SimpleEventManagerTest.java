package fr.farmvivi.fluxcord.core.event;

import fr.farmvivi.fluxcord.core.testing.StubPlugin;
import fr.farmvivi.fluxcord.api.event.Cancellable;
import fr.farmvivi.fluxcord.api.event.Event;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.event.EventPriority;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization of the internal event bus: registration rules, priority order, cancellation,
 * exact-class dispatch and per-plugin cleanup.
 */
@Timeout(10)
class SimpleEventManagerTest {

    // --- fixtures -------------------------------------------------------------------------------

    /**
     * The shared stub, declared here on purpose.
     *
     * <p>{@code SimpleEventManager} auto-registers the event types it finds in the <em>plugin class's own
     * package</em>, so this stub has to live beside the test events. Using
     * {@code fr.farmvivi.fluxcord.core.testing.StubPlugin} directly makes the manager scan that package
     * instead and find nothing — which is exactly how this test failed when the stub was deduplicated.
     */
    static class EventPackagePlugin extends StubPlugin {
        EventPackagePlugin(String id) {
            super(id);
        }
    }

    interface BaseEvent extends Event { }

    static class ChildEvent implements BaseEvent { }

    static class OtherEvent implements Event { }

    static class CancellableEvent implements Event, Cancellable {
        private boolean cancelled;

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    /** Records every priority in call order. */
    static class PriorityListener {
        final List<String> calls = new ArrayList<>();

        @EventHandler(priority = EventPriority.MONITOR) public void monitor(ChildEvent e) { calls.add("MONITOR"); }
        @EventHandler(priority = EventPriority.LOWEST) public void lowest(ChildEvent e) { calls.add("LOWEST"); }
        @EventHandler(priority = EventPriority.HIGH) public void high(ChildEvent e) { calls.add("HIGH"); }
        @EventHandler public void normal(ChildEvent e) { calls.add("NORMAL"); }
        @EventHandler(priority = EventPriority.HIGHEST) public void highest(ChildEvent e) { calls.add("HIGHEST"); }
        @EventHandler(priority = EventPriority.LOW) public void low(ChildEvent e) { calls.add("LOW"); }
    }

    private final SimpleEventManager manager = new SimpleEventManager();
    private final EventPackagePlugin plugin = new EventPackagePlugin("a");

    @AfterEach
    void shutdown() {
        manager.shutdown();
    }

    // --- registration ---------------------------------------------------------------------------

    @Test
    void registersOnlyWellFormedPublicHandlers() {
        class Listener {
            int good;

            @EventHandler public void good(ChildEvent e) { good++; }
            @EventHandler public void twoParams(ChildEvent e, String x) { fail("must not be registered"); }
            @EventHandler public void notAnEvent(String s) { fail("must not be registered"); }
            @EventHandler void packagePrivate(ChildEvent e) { fail("only public methods are scanned"); }
            public void notAnnotated(ChildEvent e) { fail("must not be registered"); }
        }
        Listener listener = new Listener();

        manager.registerListener(listener, plugin);

        assertTrue(manager.isListenerRegistered(listener));
        assertSame(plugin, manager.getOwningPlugin(listener));
        assertEquals(1, manager.getTotalHandlerCount());
        assertEquals(1, manager.getHandlerCount(ChildEvent.class));
        manager.fireEvent(new ChildEvent());
        assertEquals(1, listener.good);
    }

    @Test
    void listenerWithoutHandlersIsNotTracked() {
        Object listener = new Object();
        manager.registerListener(listener, plugin);

        assertFalse(manager.isListenerRegistered(listener));
        assertFalse(manager.unregisterListener(listener));
        assertTrue(manager.getRegisteredListeners(plugin).isEmpty());
    }

    @Test
    void sameListenerCannotBeRegisteredTwice() {
        PriorityListener listener = new PriorityListener();
        manager.registerListener(listener, plugin);
        manager.registerListener(listener, new EventPackagePlugin("b")); // refused, still owned by "a"

        assertSame(plugin, manager.getOwningPlugin(listener));
        assertEquals(6, manager.getTotalHandlerCount());
        manager.fireEvent(new ChildEvent());
        assertEquals(6, listener.calls.size(), "handlers must not be duplicated");
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> manager.registerListener(null, plugin));
        assertThrows(IllegalArgumentException.class, () -> manager.registerListener(new Object(), null));
        assertThrows(IllegalArgumentException.class, () -> manager.fireEvent(null));
        assertThrows(IllegalArgumentException.class, () -> manager.fireEventAsync(null));
        assertFalse(manager.unregisterListener(null));
        assertEquals(0, manager.unregisterAll(null));
    }

    // --- dispatch -------------------------------------------------------------------------------

    @Test
    void prioritiesRunFromLowestToMonitor() {
        PriorityListener listener = new PriorityListener();
        manager.registerListener(listener, plugin);

        ChildEvent event = new ChildEvent();
        assertSame(event, manager.fireEvent(event), "fireEvent returns the same instance");
        assertEquals(List.of("LOWEST", "LOW", "NORMAL", "HIGH", "HIGHEST", "MONITOR"), listener.calls);
    }

    @Test
    void handlersOfTheSamePriorityRunInRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        class First { @EventHandler public void on(ChildEvent e) { calls.add("first"); } }
        class Second { @EventHandler public void on(ChildEvent e) { calls.add("second"); } }

        manager.registerListener(new First(), plugin);
        manager.registerListener(new Second(), plugin);
        manager.fireEvent(new ChildEvent());

        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void supertypeHandlersReceiveSubclassEvents() {
        // E1: within a priority, the concrete class runs first, then the supertypes nearest first.
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler public void any(Event e) { calls.add("event"); }
            @EventHandler public void base(BaseEvent e) { calls.add("base"); }
            @EventHandler public void child(ChildEvent e) { calls.add("child"); }
            @EventHandler public void other(OtherEvent e) { calls.add("other"); }
        }
        manager.registerListener(new Listener(), plugin);

        manager.fireEvent(new ChildEvent());
        assertEquals(List.of("child", "base", "event"), calls);

        calls.clear();
        manager.fireEvent(new OtherEvent());
        assertEquals(List.of("other", "event"), calls);
    }

    @Test
    void priorityWinsOverHierarchyDepth() {
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler(priority = EventPriority.LOW) public void anyLow(Event e) { calls.add("event-LOW"); }
            @EventHandler(priority = EventPriority.HIGH) public void childHigh(ChildEvent e) { calls.add("child-HIGH"); }
        }
        manager.registerListener(new Listener(), plugin);

        manager.fireEvent(new ChildEvent());

        assertEquals(List.of("event-LOW", "child-HIGH"), calls);
    }

    @Test
    void supertypeHandlerCanCancelForSubclassHandlers() {
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler(priority = EventPriority.LOWEST) public void veto(Event e) { ((Cancellable) e).setCancelled(true); }
            @EventHandler(ignoreCancelled = true) public void concrete(CancellableEvent e) { calls.add("concrete"); }
        }
        manager.registerListener(new Listener(), plugin);

        assertTrue(manager.fireEvent(new CancellableEvent()).isCancelled());
        assertTrue(calls.isEmpty());
    }

    @Test
    void eventWithoutHandlersIsReturnedUntouched() {
        OtherEvent event = new OtherEvent();
        assertSame(event, manager.fireEvent(event));
    }

    @Test
    void failingHandlerIsLoggedAndOthersStillRun() {
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler(priority = EventPriority.LOW) public void boom(ChildEvent e) { throw new IllegalStateException("boom"); }
            @EventHandler public void after(ChildEvent e) { calls.add("after"); }
        }
        manager.registerListener(new Listener(), plugin);

        assertDoesNotThrow(() -> manager.fireEvent(new ChildEvent()));
        assertEquals(List.of("after"), calls);
    }

    // --- cancellation ---------------------------------------------------------------------------

    @Test
    void cancellingSkipsOnlyHandlersThatIgnoreCancelled() {
        // Bukkit semantics: ignoreCancelled = true means "skip me once cancelled"; others still run.
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler(priority = EventPriority.LOW)
            public void cancel(CancellableEvent e) { calls.add("cancel"); e.setCancelled(true); }

            @EventHandler
            public void stillCalled(CancellableEvent e) { calls.add("stillCalled"); }

            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void skipped(CancellableEvent e) { calls.add("skipped"); }

            @EventHandler(priority = EventPriority.MONITOR)
            public void monitor(CancellableEvent e) { calls.add("monitor"); }
        }
        manager.registerListener(new Listener(), plugin);

        CancellableEvent event = manager.fireEvent(new CancellableEvent());

        assertTrue(event.isCancelled());
        assertEquals(List.of("cancel", "stillCalled", "monitor"), calls);
    }

    @Test
    void unCancellingResumesIgnoreCancelledHandlers() {
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            public void cancel(CancellableEvent e) { e.setCancelled(true); }

            @EventHandler(priority = EventPriority.LOW)
            public void restore(CancellableEvent e) { e.setCancelled(false); }

            @EventHandler(ignoreCancelled = true)
            public void strict(CancellableEvent e) { calls.add("strict"); }
        }
        manager.registerListener(new Listener(), plugin);

        CancellableEvent event = manager.fireEvent(new CancellableEvent());

        assertFalse(event.isCancelled());
        assertEquals(List.of("strict"), calls);
    }

    @Test
    void eventAlreadyCancelledBeforeFiringSkipsIgnoreCancelledHandlers() {
        List<String> calls = new ArrayList<>();
        class Listener {
            @EventHandler public void normal(CancellableEvent e) { calls.add("normal"); }
            @EventHandler(ignoreCancelled = true) public void strict(CancellableEvent e) { calls.add("strict"); }
        }
        manager.registerListener(new Listener(), plugin);

        CancellableEvent event = new CancellableEvent();
        event.setCancelled(true);
        manager.fireEvent(event);

        assertEquals(List.of("normal"), calls);
    }

    // --- unregistration -------------------------------------------------------------------------

    @Test
    void unregisterListenerRemovesItsHandlersOnly() {
        PriorityListener a = new PriorityListener();
        PriorityListener b = new PriorityListener();
        manager.registerListener(a, plugin);
        manager.registerListener(b, plugin);

        assertTrue(manager.unregisterListener(a));
        assertFalse(manager.unregisterListener(a), "second call is a no-op");

        assertFalse(manager.isListenerRegistered(a));
        assertNull(manager.getOwningPlugin(a));
        assertEquals(6, manager.getTotalHandlerCount());
        manager.fireEvent(new ChildEvent());
        assertTrue(a.calls.isEmpty());
        assertEquals(6, b.calls.size());
    }

    @Test
    void unregisterAllOnlyTouchesThePluginsListeners() {
        EventPackagePlugin other = new EventPackagePlugin("b");
        PriorityListener mine1 = new PriorityListener();
        PriorityListener mine2 = new PriorityListener();
        PriorityListener theirs = new PriorityListener();
        manager.registerListener(mine1, plugin);
        manager.registerListener(mine2, plugin);
        manager.registerListener(theirs, other);
        assertEquals(2, manager.getRegisteredListeners(plugin).size());

        assertEquals(2, manager.unregisterAll(plugin));
        assertEquals(0, manager.unregisterAll(plugin));

        assertTrue(manager.getRegisteredListeners(plugin).isEmpty());
        assertEquals(1, manager.getRegisteredListeners(other).size());
        manager.fireEvent(new ChildEvent());
        assertTrue(mine1.calls.isEmpty());
        assertTrue(mine2.calls.isEmpty());
        assertEquals(6, theirs.calls.size());
        assertEquals(6, manager.getTotalHandlerCount());
    }

    @Test
    void emptyEventTypesAreDroppedFromTheCounts() {
        PriorityListener listener = new PriorityListener();
        manager.registerListener(listener, plugin);
        manager.unregisterListener(listener);

        assertEquals(0, manager.getHandlerCount(ChildEvent.class));
        assertTrue(manager.getHandlerCounts().isEmpty());
        assertTrue(manager.getEventHandlers(ChildEvent.class).isEmpty());
    }

    @Test
    void handlerViewsAreCopies() {
        manager.registerListener(new PriorityListener(), plugin);

        Map<EventPriority, ?> view = manager.getEventHandlers(ChildEvent.class);
        assertEquals(6, view.size());
        view.clear();
        assertEquals(6, manager.getHandlerCount(ChildEvent.class));

        manager.getRegisteredListeners(plugin).clear();
        assertEquals(1, manager.getRegisteredListeners(plugin).size());
    }

    @Test
    void registeringDuringDispatchIsSafe() {
        // Characterization: the handler list of each priority is snapshotted when that priority is reached,
        // so a listener registered mid-dispatch is skipped for the current priority but still sees the
        // current event at a later priority. No exception either way.
        List<String> calls = new ArrayList<>();
        class LateSame { @EventHandler(priority = EventPriority.LOWEST) public void on(ChildEvent e) { calls.add("late-same"); } }
        class LateNext { @EventHandler public void on(ChildEvent e) { calls.add("late-next"); } }
        class Registrar {
            @EventHandler(priority = EventPriority.LOWEST)
            public void on(ChildEvent e) {
                calls.add("registrar");
                manager.registerListener(new LateSame(), plugin);
                manager.registerListener(new LateNext(), plugin);
            }
        }
        manager.registerListener(new Registrar(), plugin);

        manager.fireEvent(new ChildEvent());
        assertEquals(List.of("registrar", "late-next"), calls);

        calls.clear();
        manager.fireEvent(new ChildEvent());
        assertEquals(List.of("registrar", "late-same", "late-next"), calls.subList(0, 3));
    }

    @Test
    void hasListenersFollowsTheHierarchyAndRegistrations() {
        assertFalse(manager.hasListeners(ChildEvent.class));
        assertFalse(manager.hasListeners(null));

        class OnBase { @EventHandler public void on(BaseEvent e) { } }
        OnBase listener = new OnBase();
        manager.registerListener(listener, plugin);

        assertTrue(manager.hasListeners(ChildEvent.class), "a supertype handler counts");
        assertTrue(manager.hasListeners(BaseEvent.class));
        assertFalse(manager.hasListeners(OtherEvent.class));

        manager.unregisterListener(listener);
        assertFalse(manager.hasListeners(ChildEvent.class));
    }

    // --- event type registry --------------------------------------------------------------------

    @Test
    void eventTypesAreAutoRegisteredForThePluginsOwnPackage() {
        // The stub plugin lives in fr.farmvivi.fluxcord.core.event, like the test events.
        manager.registerListener(new PriorityListener(), plugin);

        assertSame(plugin, manager.getEventTypeOwner(ChildEvent.class));
        assertEquals("Auto-registered event", manager.getEventTypeInfo(ChildEvent.class).getDescription());
        assertEquals(1, manager.getPluginEventTypes(plugin).size());
        assertTrue(manager.getRegisteredEventTypes().contains(ChildEvent.class));
    }

    @Test
    void explicitEventTypeRegistrationIsFirstComeFirstServed() {
        EventPackagePlugin other = new EventPackagePlugin("b");
        assertTrue(manager.registerEventType(OtherEvent.class, plugin, "mine"));
        assertFalse(manager.registerEventType(OtherEvent.class, other, "theirs"));

        assertSame(plugin, manager.getEventTypeOwner(OtherEvent.class));
        assertEquals("mine", manager.getEventTypeInfo(OtherEvent.class).getDescription());

        assertEquals(1, manager.unregisterEventTypes(plugin));
        assertNull(manager.getEventTypeOwner(OtherEvent.class));
        assertEquals(0, manager.unregisterEventTypes(plugin));
    }

    // --- async ----------------------------------------------------------------------------------

    @Test
    void asyncDispatchRunsOnAnotherThread() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();
        class Listener {
            @EventHandler public void on(ChildEvent e) { thread.set(Thread.currentThread()); latch.countDown(); }
        }
        manager.registerListener(new Listener(), plugin);

        manager.fireEventAsync(new ChildEvent());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), thread.get());
        assertTrue(thread.get().isDaemon());
    }
}
