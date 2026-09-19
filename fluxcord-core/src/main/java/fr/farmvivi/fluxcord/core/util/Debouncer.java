package fr.farmvivi.fluxcord.core.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Debouncer {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final long delayMillis;
    private final Runnable action;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Debouncer-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> future;

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
     */
    public synchronized void debounce() {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Annule l'exécution en attente (si elle n'a pas encore démarré) et attend la fin d'une exécution en cours.
     * Après cet appel, l'action ne s'exécutera plus tant que {@link #debounce()} n'est pas rappelé.
     * L'appelant peut ensuite exécuter l'action lui-même de manière synchrone sans risque de course.
     *
     * @param timeout délai maximal d'attente d'une exécution en cours
     * @param unit    unité du délai
     * @return true si aucune exécution n'est encore en cours à la sortie
     */
    public boolean cancelAndAwait(long timeout, TimeUnit unit) {
        ScheduledFuture<?> pending;
        synchronized (this) {
            pending = future;
            future = null;
        }
        if (pending == null || pending.isDone()) {
            return true;
        }
        // cancel(false) : n'interrompt pas une exécution déjà démarrée (l'écriture doit se terminer proprement)
        pending.cancel(false);
        if (pending.isCancelled()) {
            return true;
        }
        try {
            pending.get(timeout, unit);
            return true;
        } catch (java.util.concurrent.CancellationException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // ExecutionException (l'action a échoué) ou TimeoutException : l'exécution n'est plus/pas terminée proprement
            return e instanceof java.util.concurrent.ExecutionException;
        }
    }

    /**
     * Arrête l'exécuteur sous-jacent après avoir annulé/attendu l'exécution en attente.
     */
    public void shutdown() {
        cancelAndAwait(5, TimeUnit.SECONDS);
        executor.shutdownNow();
    }
}
