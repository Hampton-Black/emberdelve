package dm.replay;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.SessionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void recordedSessionReplays() {
        var result = ReplayRunner.replay(SESSION);

        assertTrue(result.matched(),
                "divergence at: " + result.firstDivergence());
        assertTrue(result.compared() > 0, "an empty comparison proves nothing");
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
    @DisplayName("running out of recorded dice is loud, not a fresh random roll")
    void exhaustedDiceThrow() {
        var roller = new ReplayDiceRoller(java.util.List.of());
        var thrown = assertThrows(IllegalStateException.class,
                () -> roller.roll(dm.model.RollRequest.initiative("fighter", 2)));
        assertTrue(thrown.getMessage().contains("recorded"));
    }
}
