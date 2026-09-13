package dm.engine;

import dm.content.ContentLoader;
import dm.model.ConsequenceId;
import dm.model.Disposition;
import dm.model.Event;
import dm.model.Mode;
import dm.replay.ReplayRunner;
import dm.state.EventLog;
import dm.state.SessionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.18: about three rooms hold a fight. Spec §5a / §14.
 * Chapel stays empty so a rest after the reliquary is still a decision.
 */
class OccupantsTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine site(ClockDraw draw) {
        var engine = new GameEngine(CONTENT, new EventLog(),
                new ScriptedDiceRoller(10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"),
                draw);
        engine.start();
        return engine;
    }

    private static GameEngine site() {
        return site(ClockDraw.random());
    }

    private static void toChapel(GameEngine engine) {
        engine.crossExit("door-north");
        engine.crossExit("door-north");
    }

    private static void toUndercroft(GameEngine engine) {
        toChapel(engine);
        engine.crossExit("door-north");
    }

    private static List<String> livingHostileIds(GameEngine engine) {
        return engine.state().entitiesHere().stream()
                .filter(e -> e.isAlive() && !e.isPlayerControlled())
                .map(e -> e.id())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("SCHEMA_VERSION stays 3")
    void schemaStays3() {
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("the chapel holds no fight, so a rest after the reliquary is still possible")
    void chapelIsEmpty() {
        var engine = site();
        toChapel(engine);
        assertEquals("chapel", engine.state().roomId());
        assertTrue(livingHostileIds(engine).isEmpty());
        assertFalse(engine.combat().isActive());
        assertTrue(engine.canRest());
    }

    @Test
    @DisplayName("first visit to the undercroft spawns two WARY goblins and does not start a fight")
    void undercroftIsTwoMobs() {
        var engine = site();
        toUndercroft(engine);

        assertEquals("undercroft", engine.state().roomId());
        var goblins = engine.state().entitiesHere().stream()
                .filter(e -> e.isAlive() && "goblin".equals(e.kind()))
                .toList();
        assertEquals(2, goblins.size());
        assertEquals(Mode.EXPLORATION, engine.state().mode());
        assertFalse(engine.combat().isActive());
        assertFalse(engine.canRest(), "a living hostile, even WARY, blocks rest");
    }

    @Test
    @DisplayName("coming back to the undercroft does not mint a second pair")
    void returnDoesNotRespawn() {
        var engine = site();
        toUndercroft(engine);
        var first = livingHostileIds(engine);

        engine.crossExit("door-south");
        engine.crossExit("door-north");

        assertEquals(first, livingHostileIds(engine));
        assertEquals(2, engine.log().events().stream().filter(Event.EntitySpawned.class::isInstance).count());
    }

    @Test
    @DisplayName("first visit to the vault spawns a HOSTILE brute and starts a fight")
    void vaultIsTheBrute() {
        var engine = site();
        toUndercroft(engine);
        engine.crossExit("door-north");

        assertEquals("vault", engine.state().roomId());
        var brute = engine.state().entitiesHere().stream()
                .filter(e -> "brute".equals(e.kind()))
                .toList();
        assertEquals(1, brute.size());
        assertTrue(engine.combat().isActive());
        assertEquals(Mode.COMBAT, engine.state().mode());
    }

    @Test
    @DisplayName("ALERT still offers PATROL when only the brute is here, and drops it at three hostiles")
    void patrolFattensADesignedFightUpToThree() {
        var seen = new ArrayList<List<ConsequenceId>>();
        ClockDraw capture = options -> {
            seen.add(List.copyOf(options));
            return options.contains(ConsequenceId.IT_PASSES_BY)
                    ? ConsequenceId.IT_PASSES_BY
                    : options.getFirst();
        };
        var engine = site(capture);
        toUndercroft(engine);
        engine.crossExit("door-north");
        assertTrue(engine.combat().isActive(), "setup: vault fight");

        seen.clear();
        for (int i = 0; i < 6; i++) {
            engine.tickClock(dm.model.ClockId.ALERT);
        }
        assertFalse(seen.isEmpty());
        assertTrue(seen.getLast().contains(ConsequenceId.PATROL_ARRIVES),
                "a brute alone is a fight ALERT can still fatten: " + seen.getLast());

        engine.spawnGoblin(3, 3);
        engine.spawnGoblin(4, 3);
        seen.clear();
        for (int i = 0; i < 6; i++) {
            engine.tickClock(dm.model.ClockId.ALERT);
        }
        assertFalse(seen.isEmpty());
        assertFalse(seen.getLast().contains(ConsequenceId.PATROL_ARRIVES),
                "brute+2 is never designed and ALERT stops adding: " + seen.getLast());
        assertFalse(seen.getLast().contains(ConsequenceId.SOMETHING_WANDERS_IN),
                seen.getLast().toString());
    }

    @Test
    @DisplayName("authored occupants are closed kinds with a disposition, never brute+two goblins in one room")
    void authoredLayoutMatchesTheLadder() {
        assertEquals(Disposition.WARY, CONTENT.room("undercroft").occupants().getFirst().disposition());
        assertEquals(Disposition.HOSTILE, CONTENT.room("vault").occupants().getFirst().disposition());
        assertEquals("brute", CONTENT.room("vault").occupants().getFirst().kind());
        assertTrue(CONTENT.room("chapel").occupants().isEmpty());
        assertTrue(CONTENT.room("gallery").occupants().isEmpty());
        assertTrue(CONTENT.room("crypt").occupants().isEmpty(),
                "the crypt's goblin is in the sarcophagus, not waiting in the room");
        for (var roomId : List.of("crypt", "gallery", "chapel", "undercroft", "vault")) {
            var occ = CONTENT.room(roomId).occupants();
            long goblins = occ.stream().filter(o -> "goblin".equals(o.kind())).count();
            long brutes = occ.stream().filter(o -> "brute".equals(o.kind())).count();
            assertFalse(brutes >= 1 && goblins >= 2,
                    roomId + " authors brute+2 mobs; that is ALERT's job");
        }
    }

    @Test
    @DisplayName("a first visit to the undercroft replays without minting a second pair")
    void firstVisitReplaysWithoutDoubleSpawning(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var engine = new GameEngine(CONTENT, log,
                new ScriptedDiceRoller(10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        toUndercroft(engine);
        writer.close();

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }

    @Test
    @DisplayName("a HOSTILE vault door replays without starting the fight twice")
    void vaultFightReplays(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L,
                "none", "none"));
        var engine = new GameEngine(CONTENT, log,
                new ScriptedDiceRoller(10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        toUndercroft(engine);
        engine.crossExit("door-north");
        writer.close();

        assertTrue(engine.combat().isActive());
        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());
    }
}
