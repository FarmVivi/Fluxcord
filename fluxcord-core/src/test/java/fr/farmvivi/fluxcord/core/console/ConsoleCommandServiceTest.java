package fr.farmvivi.fluxcord.core.console;

import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The stdin reader: one line = one console command, blank lines skipped, EOF ends the loop, stop is idempotent. */
class ConsoleCommandServiceTest {

    private final SimpleCommandService commandService = mock(SimpleCommandService.class);
    private final JDA jda = mock(JDA.class);
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void linesBecomeConsoleCommandEventsUntilEof() throws Exception {
        ConsoleCommandService service = new ConsoleCommandService(commandService,
                new ByteArrayInputStream("help\n   \n  perm nodes  \n".getBytes(StandardCharsets.UTF_8)));
        service.setJDA(jda);

        service.start();
        assertTrue(service.isRunning());
        service.start(); // idempotent
        ArgumentCaptor<ConsoleCommandEvent> events = ArgumentCaptor.forClass(ConsoleCommandEvent.class);
        verify(commandService, timeout(2000).times(2)).processCommand(events.capture());
        assertEquals(List.of("help", "perm nodes"), events.getAllValues().stream().map(ConsoleCommandEvent::getInput).toList());
        assertSame(jda, events.getValue().getJDA());

        service.stop();
        assertFalse(service.isRunning());
        service.stop(); // idempotent
    }

    @Test
    void commandsBeforeTheConnectionAreRefusedAndFailuresDoNotStopTheLoop() throws Exception {
        PipedOutputStream writer = new PipedOutputStream();
        PipedInputStream reader = new PipedInputStream(writer);
        ConsoleCommandService service = new ConsoleCommandService(commandService, reader);
        service.start();

        writer.write("early\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        waitFor(() -> console.toString(StandardCharsets.UTF_8).contains("not connected"));
        verify(commandService, never()).processCommand(any());

        service.setJDA(jda);
        doThrow(new IllegalStateException("kaboom")).when(commandService).processCommand(any());
        writer.write("boom\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        waitFor(() -> console.toString(StandardCharsets.UTF_8).contains("Error executing command: kaboom"));

        doNothing().when(commandService).processCommand(any());
        writer.write("again\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        verify(commandService, timeout(2000).times(2)).processCommand(any());
        assertTrue(service.isRunning(), "still alive after a failing command");

        service.stop();
        writer.close();
    }

    @Test
    void aForeignCommandServiceIsReportedInsteadOfCrashing() throws Exception {
        CommandService other = mock(CommandService.class);
        ConsoleCommandService service = new ConsoleCommandService(other,
                new ByteArrayInputStream("help\n".getBytes(StandardCharsets.UTF_8)));
        service.setJDA(jda);
        service.start();
        waitFor(() -> console.toString(StandardCharsets.UTF_8).contains("not supported"));
        service.stop();
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 2s");
            }
            Thread.sleep(20);
        }
    }
}
