package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ToolSource;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Mood;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the conversation's state really is owned by one thread.
 *
 * <p>Separate from {@link RealtimeConversationTest}, which runs everything on the calling thread so its
 * assertions stay about behaviour rather than timing. This one uses the real executors, because the two
 * properties worth pinning are the ones only real threads can show: that work posted from anywhere runs on
 * the inbox, and that a tool which blocks does not block the audio behind it.
 *
 * <p>Why it matters: three threads already mutated {@code turnOpen}, {@code heard}, {@code speaking} and two
 * clocks with nothing between them — the audio thread, the turn ticker and the websocket. It happened to
 * work, which is the worst state for code to be in.
 */
class RealtimeInboxTest {

    private static final long NOW = 2_000_000L;

    private final ExecutorService inbox = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "test-inbox"));
    private final ExecutorService workers = Executors.newCachedThreadPool(
            runnable -> new Thread(runnable, "test-worker"));

    /** The thread each frame was sent from, in order. */
    private final ConcurrentLinkedQueue<String> senders = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> frames = new ConcurrentLinkedQueue<>();

    /** Held until the test lets the tool finish, so "blocking" is a certainty and not a race. */
    private final CountDownLatch toolMayFinish = new CountDownLatch(1);
    private final CountDownLatch toolStarted = new CountDownLatch(1);

    private Consumer<RealtimeProtocol.Event> events;

    @AfterEach
    void tearDown() {
        toolMayFinish.countDown();
        inbox.shutdownNow();
        workers.shutdownNow();
    }

    @Test
    void workPostedFromAnywhereRunsOnTheInboxAndNotOnTheCaller() throws Exception {
        RealtimeConversation conversation = open();

        conversation.hear("u1", "Victor", audio(320));
        drain();

        assertTrue(senders.contains("test-inbox"), "the frames went out from the inbox thread");
        assertTrue(senders.stream().noneMatch(name -> name.startsWith("main")
                        || name.startsWith("Test worker") || name.startsWith("ForkJoinPool")),
                "and never from the caller's thread: " + senders);
    }

    @Test
    void aToolThatBlocksDoesNotHoldBackTheAudioBehindIt() throws Exception {
        RealtimeConversation conversation = open();
        drain();
        frames.clear();

        // The model asks for a tool. It will not return until this test says so.
        events.accept(new RealtimeProtocol.Event.ToolCalled(
                List.of(new ChatModel.ToolCall("c1", "slow_tool", "{}"))));
        assertTrue(toolStarted.await(2, TimeUnit.SECONDS), "the tool should have started");

        // Audio captured while the tool is still running must reach the socket now, not after it.
        conversation.hear("u1", "Victor", audio(320));
        drain();

        assertTrue(frames.stream().anyMatch(frame -> frame.contains("input_audio_buffer.append")),
                "the audio went out while the tool was still blocked: " + frames);
        assertTrue(frames.stream().noneMatch(frame -> frame.contains("function_call_output")),
                "and the tool has not answered yet, which is what makes the point");

        toolMayFinish.countDown();
        assertTrue(eventually(frame -> frame.contains("function_call_output")),
                "then its result follows");
    }

    @Test
    void theToolItselfRunsOffTheInbox() throws Exception {
        AtomicInteger ranOnInbox = new AtomicInteger();
        RealtimeConversation conversation = open(name -> {
            if ("test-inbox".equals(name)) {
                ranOnInbox.incrementAndGet();
            }
        });
        drain();

        events.accept(new RealtimeProtocol.Event.ToolCalled(
                List.of(new ChatModel.ToolCall("c1", "slow_tool", "{}"))));
        assertTrue(toolStarted.await(2, TimeUnit.SECONDS));
        toolMayFinish.countDown();
        drain();

        assertEquals(0, ranOnInbox.get(), "a tool on the inbox is a tool that stops the conversation");
        assertNotEquals(0, conversation.lastActivityMs(), "and the session is still usable");
    }

    /**
     * Waits until some frame matches, or gives up.
     *
     * <p>Not {@link #drain()}: the result of a tool travels worker -> inbox -> socket, and draining the
     * inbox only waits for what was already queued on it. Releasing the tool and draining once was a race
     * the test lost about one run in ten - the worker had not yet posted anything to drain.
     */
    private boolean eventually(java.util.function.Predicate<String> match) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (frames.stream().anyMatch(match)) {
                return true;
            }
            drain();
        }
        return false;
    }

    /** Waits for everything queued on both executors to have run. */
    private void drain() throws Exception {
        CountDownLatch idle = new CountDownLatch(1);
        inbox.execute(idle::countDown);
        assertTrue(idle.await(5, TimeUnit.SECONDS), "the inbox should not be stuck");
    }

    private RealtimeConversation open() throws Exception {
        return open(name -> {
        });
    }

    private RealtimeConversation open(Consumer<String> onToolThread) throws Exception {
        RealtimeConversation conversation = new RealtimeConversation(
                LoggerFactory.getLogger(RealtimeInboxTest.class), new OpenAiRealtime(), () -> NOW,
                List.of(slowTool(onToolThread)),
                audio -> {
                }, () -> {
        }, turn -> {
        }, message -> {
        }, true, 1_200, text -> true, inbox, workers,
                fr.farmvivi.fluxcord.plugins.aiaudio.conversation.AddressedToUs.never(), 0L);
        conversation.start(snapshot(), "You are Fluxcord.", "marin", "bot-1", sink -> {
            events = sink;
            return link();
        });
        drain();
        return conversation;
    }

    private ToolSource slowTool(Consumer<String> onToolThread) {
        return new ToolSource() {
            @Override
            public List<ChatModel.Tool> declarations() {
                return List.of(new ChatModel.Tool("slow_tool", "Takes its time", Map.of()));
            }

            @Override
            public boolean handles(String name) {
                return "slow_tool".equals(name);
            }

            @Override
            public String execute(ChatModel.ToolCall call, PersonaSnapshot snapshot, long nowMs) {
                onToolThread.accept(Thread.currentThread().getName());
                toolStarted.countDown();
                try {
                    toolMayFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }
        };
    }

    private RealtimeLink link() {
        return new RealtimeLink() {
            @Override
            public void send(String frame) {
                senders.add(Thread.currentThread().getName());
                frames.add(frame);
            }

            @Override
            public void close() {
                // nothing to release
            }

            @Override
            public boolean isOpen() {
                return true;
            }
        };
    }

    private static PcmAudio audio(int bytes) {
        return new PcmAudio(new byte[bytes], OpenAiRealtime.SAMPLE_RATE, 1);
    }

    private static PersonaSnapshot snapshot() {
        ConversationContext conversation = new ConversationContext("g1", "My Server", "c1", "General",
                List.of(), List.of(), List.of());
        return new PersonaSnapshot(new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                Mood.neutral(NOW), conversation,
                List.of(new PersonaSnapshot.Acquaintance("u1", "Victor",
                        PersonaSnapshot.Acquaintance.Level.REGULAR, 10)));
    }
}
