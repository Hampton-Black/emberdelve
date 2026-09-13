package dm.ai;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-xgg.12: every click crossing narrates via {@link DmService#narrateArrival}.
 */
class ArrivalNarrationTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("noteCrossing plus narrateArrival is one prose call with ARRIVAL_FIRST")
    void clickCrossingNarratesArrival() {
        var engine = engine();
        var prose = new ScriptedDmClient("Cold stone underfoot.");
        var dm = dm(engine, prose);

        dm.noteCrossing("The Ashen Crypt", "The Long Gallery", false);

        var narrated = new AtomicBoolean(false);
        dm.narrateArrival(new TurnSink() {
            @Override public void narration(dm.model.NarrationSegment segment) {
                assertEquals("Cold stone underfoot.", segment.text());
                narrated.set(true);
            }
            @Override public void diffs(java.util.List<dm.model.Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() {}
        });

        assertTrue(narrated.get());
        assertEquals(1, prose.conversations().size());
        var conversation = prose.conversations().getFirst();
        var directive = conversation.stream()
                .filter(m -> "user".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .filter(c -> c != null && c.contains("Three sentences at most."))
                .findFirst()
                .orElseThrow();
        assertTrue(directive.contains(DmService.ARRIVAL_FIRST), directive);
        assertTrue(conversation.stream()
                .anyMatch(m -> m.content() != null
                        && m.content().contains(DmService.thresholdMarker(
                                "The Ashen Crypt", "The Long Gallery"))));
    }

    @Test
    @DisplayName("a return crossing uses ARRIVAL_RETURN")
    void returnCrossingUsesReturnRegister() {
        var engine = engine();
        var prose = new ScriptedDmClient("Back again.");
        var dm = dm(engine, prose);

        dm.noteCrossing("The Long Gallery", "The Ashen Crypt", true);
        dm.narrateArrival(silentSink());

        var directive = prose.conversations().getFirst().stream()
                .filter(m -> "user".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .filter(c -> c != null && c.contains("Three sentences at most."))
                .findFirst()
                .orElseThrow();
        assertTrue(directive.contains(DmService.ARRIVAL_RETURN), directive);
    }

    @Test
    @DisplayName("an empty rail still completes without a prose call")
    void emptyRailCompletesWithoutProse() {
        var engine = engine();
        var prose = new ScriptedDmClient("should not run");
        var dm = dm(engine, prose);

        dm.noteCrossing("The Ashen Crypt", "The Long Gallery", false);
        engine.directives().drain();

        var completed = new AtomicBoolean(false);
        dm.narrateArrival(new TurnSink() {
            @Override public void narration(dm.model.NarrationSegment segment) {}
            @Override public void diffs(java.util.List<dm.model.Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() { completed.set(true); }
        });

        assertTrue(completed.get());
        assertTrue(prose.conversations().isEmpty());
    }

    private static GameEngine engine() {
        var engine = new GameEngine(CONTENT, new EventLog(), new ScriptedDiceRoller(10, 10, 10, 10),
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
            @Override public void narration(dm.model.NarrationSegment segment) {}
            @Override public void diffs(java.util.List<dm.model.Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() {}
        };
    }
}
