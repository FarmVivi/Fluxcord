package fr.farmvivi.fluxcord.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DebouncerTest {

    @Test
    void rapidCallsRunTheActionOnce() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ran = new CountDownLatch(1);
        Debouncer debouncer = new Debouncer(50, () -> {
            runs.incrementAndGet();
            ran.countDown();
        });

        for (int i = 0; i < 20; i++) {
            debouncer.debounce();
        }

        assertTrue(ran.await(2, TimeUnit.SECONDS), "the action must run after the delay");
        Thread.sleep(150); // give a hypothetical second run the time to show up
        assertEquals(1, runs.get(), "20 calls within the delay must coalesce into one run");
        debouncer.shutdown();
    }

    @Test
    void separatedCallsRunTheActionEachTime() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch twoRuns = new CountDownLatch(2);
        Debouncer debouncer = new Debouncer(10, () -> {
            runs.incrementAndGet();
            twoRuns.countDown();
        });

        debouncer.debounce();
        Thread.sleep(100);
        debouncer.debounce();

        assertTrue(twoRuns.await(2, TimeUnit.SECONDS));
        assertEquals(2, runs.get());
        debouncer.shutdown();
    }

    @Test
    void cancelAndAwaitDropsAPendingRun() throws InterruptedException {
        AtomicInteger runs = new AtomicInteger();
        Debouncer debouncer = new Debouncer(500, runs::incrementAndGet);

        debouncer.debounce();
        assertTrue(debouncer.cancelAndAwait(1, TimeUnit.SECONDS));
        Thread.sleep(700);

        assertEquals(0, runs.get(), "a run that had not started yet must be cancelled");
        debouncer.shutdown();
    }

    @Test
    void cancelAndAwaitWaitsForARunningAction() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Debouncer debouncer = new Debouncer(0, () -> {
            started.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        });

        debouncer.debounce();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        long before = System.nanoTime();
        assertTrue(debouncer.cancelAndAwait(2, TimeUnit.SECONDS));
        long waitedMs = (System.nanoTime() - before) / 1_000_000;

        assertEquals(0, finished.getCount(), "cancelAndAwait must return only once the running action finished");
        assertTrue(waitedMs >= 200, "expected to actually wait for the action, waited " + waitedMs + " ms");
        debouncer.shutdown();
    }

    @Test
    void shutdownDoesNotInterruptARunningActionAndPreventsFurtherRuns() throws InterruptedException {
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Debouncer debouncer = new Debouncer(0, () -> {
            started.countDown();
            try {
                Thread.sleep(200);
                completed.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        debouncer.debounce();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        debouncer.shutdown();

        assertEquals(1, completed.get(), "shutdown must let the in-flight action complete");
        assertThrows(java.util.concurrent.RejectedExecutionException.class, debouncer::debounce,
                "no run can be scheduled after shutdown");
    }

    @Test
    void cancelAndAwaitWithoutPendingRunReturnsImmediately() {
        Debouncer debouncer = new Debouncer(100, () -> {
        });
        assertTrue(debouncer.cancelAndAwait(1, TimeUnit.SECONDS));
        debouncer.shutdown();
    }

    @Test
    void workerThreadIsDaemonAndNamed() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        Thread[] worker = new Thread[1];
        Debouncer debouncer = new Debouncer(0, () -> {
            worker[0] = Thread.currentThread();
            ran.countDown();
        });

        debouncer.debounce();
        assertTrue(ran.await(2, TimeUnit.SECONDS));

        assertTrue(worker[0].isDaemon(), "a debouncer must never keep the JVM alive");
        assertTrue(worker[0].getName().startsWith("Debouncer-"), worker[0].getName());
        debouncer.shutdown();
    }
}
