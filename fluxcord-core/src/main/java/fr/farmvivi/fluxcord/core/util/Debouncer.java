package fr.farmvivi.fluxcord.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coalesces bursts of {@link #debounce()} calls into a single execution of the action, run on a
 * dedicated daemon thread once {@code delayMillis} have elapsed since the last call.
 * <p>
 * {@link #cancelAndAwait(long, TimeUnit)} / {@link #shutdown()} give callers a safe point after which
 * the action is guaranteed not to be running, so they can perform the same work synchronously
 * (e.g. a final flush to disk) without racing the background execution.
 */
public class Debouncer {
    private static final Logger logger = LoggerFactory.getLogger(Debouncer.class);
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final long delayMillis;
    private final Runnable action;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Debouncer-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    // All fields below are guarded by 'this'.
    private ScheduledFuture<?> future;
    /** Incremented on every debounce()/cancel: a scheduled run only executes if its generation is still current. */
    private long generation;
    private boolean running;

    /**
     * Crée un debouncer avec un délai spécifié et une action à exécuter.
     *
     * @param delayMillis Le délai en millisecondes
     * @param action      L'action à exécuter
     */
    public Debouncer(long delayMillis, Runnable action) {
        this.delayMillis = delayMillis;
        this.action = action;
    }

    /**
     * Déclenche l'action après le délai spécifié.
     * Si cette méthode est appelée plusieurs fois rapidement, l'action ne sera exécutée qu'une seule fois après le délai spécifié.
     *
     * @throws java.util.concurrent.RejectedExecutionException après {@link #shutdown()}
     */
    public synchronized void debounce() {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        long scheduledGeneration = ++generation;
        future = executor.schedule(() -> run(scheduledGeneration), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void run(long scheduledGeneration) {
        synchronized (this) {
            // Cancelled (or superseded) between scheduling and execution: skip without touching 'running'.
            if (scheduledGeneration != generation) {
                return;
            }
            running = true;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.error("Debounced action failed", e);
        } finally {
            synchronized (this) {
                running = false;
                notifyAll();
            }
        }
    }

    /**
     * Annule l'exécution en attente (si elle n'a pas encore démarré) et attend la fin d'une exécution en cours.
     * Après cet appel, l'action ne s'exécutera plus tant que {@link #debounce()} n'est pas rappelé, et
     * l'appelant peut exécuter l'action lui-même de manière synchrone sans risque de course.
     *
     * @param timeout délai maximal d'attente d'une exécution en cours
     * @param unit    unité du délai
     * @return true si aucune exécution n'est en cours à la sortie, false si le délai a expiré (ou interruption)
     */
    public synchronized boolean cancelAndAwait(long timeout, TimeUnit unit) {
        generation++; // invalidates any run that has not started yet
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (running) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Arrête l'exécuteur sous-jacent après avoir annulé l'exécution en attente et attendu celle en cours.
     */
    public void shutdown() {
        if (!cancelAndAwait(5, TimeUnit.SECONDS)) {
            logger.warn("Debounced action still running after 5s, shutting down anyway");
        }
        executor.shutdownNow();
    }
}
