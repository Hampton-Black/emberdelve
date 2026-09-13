package dm.ai;

import dm.ScriptedDmClient;
import dm.ai.DmClient.ChatMessage;
import dm.ai.DmClient.TurnResult;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.NarrationSegment;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
