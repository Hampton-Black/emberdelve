package dm.replay;

import dm.ai.DmClient;
import dm.ai.ToolDispatcher;
import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.SessionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of the whole batch: a session that happened, re-run against the engine, offline.
 *
 * <p>No Venice, no key, no network. The model's decisions are fixed inputs read from the file,
 * so what is under test is the engine's response to them — which means a change that alters how
 * a fight resolves fails here, against a real fight, rather than in play three weeks later.
 */
class ReplayRunnerTest {

    private static final Path SESSION =
            Path.of("src/test/resources/sessions/crypt-fight.jsonl");

    @Test
    @DisplayName("a recorded fight replays to the same events it produced the first time")
    void recordedSessionReplays() throws Exception {
        var result = ReplayRunner.replay(SESSION);

        assertTrue(result.matched(),
                "divergence at: " + result.firstDivergence());
        assertTrue(result.compared() >= 11,
                "the fixture must cover a goblin turn, not a one-swing kill; compared="
                        + result.compared());
        String jsonl = Files.readString(SESSION);
        assertTrue(jsonl.contains("\"type\":\"turn_advanced\""),
                "the fixture must contain a turn_advanced");
        assertTrue(jsonl.contains("\"killed\":true"),
                "the fixture must keep a killing blow");
    }

    @Test
    @DisplayName("a fight the goblin won initiative in replays without a false divergence")
    void goblinWonInitiativeReplays(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));
        // livingEntities() is id order: fighter then goblin. 1+1=2 vs 20+2=22 — goblin first.
        var engine = new GameEngine(new ContentLoader(), log, new ScriptedDiceRoller(1, 20));
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());
        writer.close();

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }

    @Test
    @DisplayName("a fight that lasts past the first swing replays, goblin turn included")
    void aFightThatLastsPastTheFirstSwingReplays(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));
        // Fighter init 1, goblin 20 — goblin first. Goblin hits (19) for 4. Fighter hits (14) for 4.
        var engine = new GameEngine(new ContentLoader(), log,
                new ScriptedDiceRoller(1, 20, 19, 4, 14, 4));
        var sink = new CombatSink.Buffer();
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> {});
        engine.combat().attack("fighter", "goblin", sink);
        writer.close();

        assertTrue(log.events().stream().anyMatch(Event.TurnAdvanced.class::isInstance),
                "setup: the goblin's turn must advance");
        assertTrue(log.events().stream().anyMatch(e ->
                e instanceof Event.AttackResolved a && a.killed()));

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
        assertTrue(result.compared() >= 6, "compared=" + result.compared());
    }

    @Test
    @DisplayName("an asserted fact does not fail replay because of the id the dispatcher minted")
    void anAssertedFactDoesNotFailReplayBecauseOfItsMintedId(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));
        var engine = new GameEngine(new ContentLoader(), log, new ScriptedDiceRoller(20));
        engine.start();
        var dispatched = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", "assert_fact",
                "{\"text\":\"The air tastes of old iron.\",\"anchor\":\"ambient\"}"));
        assertTrue(dispatched.ok(), dispatched.message());
        assertTrue(log.events().stream().anyMatch(Event.FactAsserted.class::isInstance));
        writer.close();

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }

    @Test
    @DisplayName("a session that walks between rooms replays into the same rooms")
    void aCrossingReplays(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var content = new ContentLoader();
        var engine = new GameEngine(content, log, new ScriptedDiceRoller(10),
                dm.engine.Rooms.authored(content, "crypt", "gallery"));
        engine.start();
        engine.crossExit("door-north");
        engine.crossExit("door-south");
        writer.close();

        assertEquals("crypt", engine.state().roomId(), "setup: back where we started");

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }

    @Test
    @DisplayName("what you leave in a room is still there when the replay comes back")
    void replayKeepsWhatWasLeftBehind(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var content = new ContentLoader();
        var engine = new GameEngine(content, log, new ScriptedDiceRoller(10),
                dm.engine.Rooms.authored(content, "crypt", "gallery"));
        engine.start();
        engine.spawnGoblin(6, 6);
        engine.revealProp("alcove");
        engine.crossExit("door-north");
        engine.crossExit("door-south");
        writer.close();

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());

        // The replay's own fold, not the recording engine's: this is the gate's "as you left it"
        // criterion, asserted mechanically before anyone plays it.
        var replayed = EventLog.load(writer.path()).state();
        assertEquals("crypt", replayed.roomId());
        assertEquals("crypt", replayed.find("goblin").orElseThrow().roomId());
        assertTrue(replayed.revealedHere().contains("alcove"));
    }

    @Test
    @DisplayName("running out of recorded dice is loud, not a fresh random roll")
    void exhaustedDiceThrow() {
        var roller = new ReplayDiceRoller(java.util.List.of());
        var thrown = assertThrows(IllegalStateException.class,
                () -> roller.roll(dm.model.RollRequest.initiative("fighter", 2)));
        assertTrue(thrown.getMessage().contains("recorded"));
    }
}
