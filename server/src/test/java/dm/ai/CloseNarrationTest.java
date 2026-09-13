package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dm.ScriptedDmClient;
import dm.ai.DmClient.ChatMessage;
import dm.ai.DmClient.TurnResult;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Ending;
import dm.model.NarrationSegment;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.5: the close is the last narration, not an extra one. Spec §8e.
 */
class CloseNarrationTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("narrateClose is prose-only, uses CLOSE, and completes")
    void closeIsProseOnlyWithCloseDirective() {
        var engine = engine();
        var prose = new ScriptedDmClient("Roderick does not get up.");
        var dm = dm(engine, prose);

        var narrated = new AtomicBoolean(false);
        dm.narrateClose(List.of("Vessk hits you for 6 damage. You are killed by the blow."),
                new TurnSink() {
                    @Override public void narration(NarrationSegment segment) {
                        assertEquals("Roderick does not get up.", segment.text());
                        narrated.set(true);
                    }
                    @Override public void diffs(List<dm.model.Diff> diffs) {}
                    @Override public void roll(dm.model.RollResult roll) {}
                    @Override public void error(Throwable error) {}
                    @Override public void complete() {}
                });

        assertTrue(narrated.get());
        assertEquals(1, prose.conversations().size());
        var user = prose.conversations().getFirst().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();
        assertTrue(user.contains(DmService.CLOSE), user);
        assertTrue(user.contains("Vessk hits you for 6 damage"));
    }

    @Test
    @DisplayName("close waits on the lock rather than giving up")
    void closeWaitsOnTheLock() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocking = new DmClient() {
            @Override
            public TurnResult streamTurn(List<ChatMessage> conversation,
                                         com.fasterxml.jackson.databind.JsonNode tools,
                                         DmListener listener) {
                started.countDown();
                try {
                    if (!release.await(4, TimeUnit.SECONDS)) {
                        fail("openScene was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
                listener.onTextDelta("Opening.");
                return new TurnResult("Opening.", List.of());
            }
            @Override public String modelId() { return "blocking"; }
            @Override public long ping() { return 0; }
        };
        var engine = engine();
        var dm = new DmService(new ScriptedDmClient(), blocking, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var opening = executor.submit(() -> dm.openScene(silentSink(), true));
        assertTrue(started.await(2, TimeUnit.SECONDS), "openScene should have taken the lock");

        var closed = new AtomicBoolean(false);
        var close = executor.submit(() -> {
            dm.narrateClose(List.of("The party has fallen."), silentSink());
            closed.set(true);
        });
        Thread.sleep(150);
        assertFalse(closed.get(), "close must wait on lock(), not tryLock-and-drop");

        release.countDown();
        opening.get(2, TimeUnit.SECONDS);
        close.get(2, TimeUnit.SECONDS);
        assertTrue(closed.get());
        executor.close();
    }

    @Test
    @DisplayName("typed use_exit of the way-out is CLOSE, not arrival or ordinary turn prose")
    void typedExtractUsesCloseDirective() {
        typedExtractIsClose(false, Ending.EXTRACTED_WITHOUT);
    }

    @Test
    @DisplayName("typed use_exit holding the reliquary is still CLOSE")
    void typedExtractHoldingUsesCloseDirective() {
        typedExtractIsClose(true, Ending.EXTRACTED_WITH_OBJECTIVE);
    }

    private static void typedExtractIsClose(boolean holding, Ending expected) {
        var engine = engine();
        if (holding) {
            engine.takeProp("reliquary");
        }
        var prose = new ScriptedDmClient("They walk out into the air.");
        var dm = new DmService(useExitOnce("stair-south"), prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));

        var completed = new AtomicBoolean(false);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                dm.handleFreeText("fighter", "I climb back up the stairs", new TurnSink() {
                    @Override public void narration(NarrationSegment segment) {}
                    @Override public void diffs(List<dm.model.Diff> diffs) {}
                    @Override public void roll(dm.model.RollResult roll) {}
                    @Override public void error(Throwable error) {}
                    @Override public void complete() { completed.set(true); }
                }),
                "must not nest narrating.lock() after use_exit ends the delve");

        assertEquals(Optional.of(expected), engine.state().ending());
        assertTrue(completed.get(), "the turn still completes");
        assertEquals(1, prose.conversations().size(), "one close, not an extra narration");
        var blob = prose.conversations().getFirst().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(blob.contains(DmService.CLOSE), blob);
        assertFalse(blob.contains(DmService.ARRIVAL_FIRST), blob);
        assertFalse(blob.contains(DmService.ARRIVAL_RETURN), blob);
    }

    /** One mechanics call of use_exit, then silence — never a nested lock. */
    private static DmClient useExitOnce(String exitId) {
        var rounds = new AtomicInteger();
        return new DmClient() {
            @Override
            public TurnResult streamTurn(List<ChatMessage> conversation, JsonNode tools,
                                         DmListener listener) {
                if (tools != null && rounds.getAndIncrement() == 0) {
                    return new TurnResult("", List.of(new ToolCall(
                            "1", ToolSchema.USE_EXIT,
                            "{\"exit_id\":\"" + exitId + "\"}")));
                }
                return new TurnResult("", List.of());
            }
            @Override public String modelId() { return "scripted"; }
            @Override public long ping() { return 0; }
        };
    }

    private static GameEngine engine() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static DmService dm(GameEngine engine, ScriptedDmClient prose) {
        return new DmService(new ScriptedDmClient(), prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
    }

    private static TurnSink silentSink() {
        return new TurnSink() {
            @Override public void narration(NarrationSegment segment) {}
            @Override public void diffs(List<dm.model.Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() {}
        };
    }
}
