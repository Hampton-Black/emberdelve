package dm.engine;

import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.Consumable;
import dm.model.Diff;
import dm.model.Ending;
import dm.model.EndingReport;
import dm.model.Event;
import dm.model.ObjectiveFate;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.5: a delve can end extracted, empty-handed, or lost.
 */
class DelveEndingTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log, Integer... faces) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(faces),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static GameEngine siteStarted(EventLog log, Integer... faces) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(faces),
                Rooms.authored(CONTENT, "crypt", "gallery", "chapel", "undercroft", "vault"));
        engine.start();
        return engine;
    }

    private static void toChapel(GameEngine engine) {
        engine.crossExit("door-north");
        engine.crossExit("door-north");
    }

    private static void backToCrypt(GameEngine engine) {
        engine.crossExit("door-south");
        engine.crossExit("door-south");
    }

    private static <T extends Event> List<T> eventsOf(EventLog log, Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    @DisplayName("killing the last player-controlled entity emits PARTY_LOST then CombatEnded")
    void killingThePartyEndsTheDelve() {
        var log = new EventLog();
        // Goblin wins initiative, hits, 4+2 damage. Fighter is dropped to 1 hp first.
        var engine = started(log, 1, 20, 19, 4);
        engine.spawnGoblin(6, 6);
        var fighter = engine.state().find("fighter").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), fighter.withHp(1)));

        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> {});

        assertFalse(engine.state().find("fighter").orElseThrow().isAlive());
        assertTrue(sink.collectedDiffs().stream().anyMatch(d -> d instanceof Diff.DelveEnded ended
                        && ended.ending().equals(engine.scene().ending())
                        && ended.ending().hurt() == null),
                "PARTY_LOST ships the report on the diff, not a bare enum");
        var ended = eventsOf(log, Event.DelveEnded.class);
        assertEquals(1, ended.size(), "PARTY_LOST is an event, not a client hp check");
        assertEquals(Ending.PARTY_LOST, ended.getFirst().ending());
        assertEquals("", ended.getFirst().throughExitId());
        assertEquals(Optional.of(Ending.PARTY_LOST), engine.state().ending());

        var types = log.events().stream().map(Event::getClass).toList();
        int lostAt = types.lastIndexOf(Event.DelveEnded.class);
        int combatOverAt = types.lastIndexOf(Event.CombatEnded.class);
        int attackAt = types.lastIndexOf(Event.AttackResolved.class);
        assertTrue(attackAt >= 0 && lostAt > attackAt,
                "DelveEnded follows the killing AttackResolved");
        assertTrue(combatOverAt > lostAt, "CombatEnded still folds the fight closed");
        assertFalse(engine.combat().isActive());
    }

    @Test
    @DisplayName("killing the last goblin ends combat and does not end the delve")
    void killingTheGoblinDoesNotEndTheDelve() {
        var log = new EventLog();
        var engine = started(log, 20, 1, 18, 4);
        engine.spawnGoblin(6, 6);
        var goblin = engine.state().find("goblin").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), goblin.withHp(1)));

        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        engine.combat().moveTo("fighter", 6, 5, sink);
        engine.combat().attack("fighter", "goblin", sink);

        assertFalse(engine.state().find("goblin").orElseThrow().isAlive());
        assertTrue(eventsOf(log, Event.DelveEnded.class).isEmpty());
        assertTrue(engine.state().ending().isEmpty());
        assertFalse(engine.combat().isActive());
        assertTrue(eventsOf(log, Event.CombatEnded.class).size() >= 1);
    }

    @Test
    @DisplayName("after PARTY_LOST every action except restart is refused")
    void refuseAfterPartyLost() {
        var log = new EventLog();
        var engine = started(log, 1, 20, 19, 4);
        engine.spawnGoblin(6, 6);
        var fighter = engine.state().find("fighter").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), fighter.withHp(1)));
        engine.combat().start(new CombatSink.Buffer());
        engine.combat().runAutomaticTurns(new CombatSink.Buffer(), () -> {});

        assertEquals(Optional.of(Ending.PARTY_LOST), engine.state().ending());
        assertThrows(IllegalArgumentException.class, () -> engine.takeProp("reliquary"));
        assertThrows(IllegalArgumentException.class, () -> engine.crossExit("door-north"));
        assertThrows(IllegalArgumentException.class,
                () -> engine.moveTo("fighter", 5, 5, new CombatSink.Buffer()));
        assertThrows(IllegalArgumentException.class, () -> engine.revealProp("alcove"));

        engine.restart();
        assertTrue(engine.state().ending().isEmpty());
        assertEquals("crypt", engine.state().roomId());
        assertTrue(engine.state().find("fighter").orElseThrow().isAlive());
    }

    @Test
    @DisplayName("PARTY_LOST ships a report with sentence parts and no hurt")
    void partyLostShipsReport() {
        var log = new EventLog();
        var engine = lost(log);

        EndingReport report = engine.scene().ending();
        assertNotNull(report);
        assertEquals(Ending.PARTY_LOST, report.ending());
        assertEquals(List.of("Roderick"), report.partyNames());
        assertFalse(report.partyNames().contains("fighter"));
        assertEquals("The Ashen Crypt", report.roomName());
        assertEquals("reliquary", report.objectiveName());
        assertNull(report.hurt());
        assertEquals(ObjectiveFate.LEFT_WHERE_IT_LAY, report.objective());
        assertEquals(3, Event.SCHEMA_VERSION);
        assertEquals(1, report.roomsEntered());
        assertEquals(2, report.roomsInSite());
    }

    @Test
    @DisplayName("extract with the reliquary ships EXTRACTED_WITH_OBJECTIVE, hurt, and CARRIED_OUT")
    void extractWithObjectiveShipsReport() {
        var log = new EventLog();
        var engine = siteStarted(log, 10);
        toChapel(engine);
        engine.takeProp("reliquary");
        backToCrypt(engine);
        var diffs = engine.crossExit("stair-south");

        EndingReport report = engine.scene().ending();
        assertNotNull(report);
        assertEquals(Ending.EXTRACTED_WITH_OBJECTIVE, report.ending());
        assertEquals(List.of("Roderick"), report.partyNames());
        assertEquals("The Ashen Crypt", report.roomName());
        assertEquals("reliquary", report.objectiveName());
        assertEquals("barely marked", report.hurt());
        assertEquals(ObjectiveFate.CARRIED_OUT, report.objective());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.DelveEnded ended
                && ended.ending().equals(report)));
    }

    @Test
    @DisplayName("extract without the reliquary ships EXTRACTED_WITHOUT, hurt, and LEFT_WHERE_IT_LAY")
    void extractWithoutShipsReport() {
        var log = new EventLog();
        var engine = started(log, 10);
        engine.crossExit("stair-south");

        EndingReport report = engine.scene().ending();
        assertNotNull(report);
        assertEquals(Ending.EXTRACTED_WITHOUT, report.ending());
        assertEquals("barely marked", report.hurt());
        assertEquals(ObjectiveFate.LEFT_WHERE_IT_LAY, report.objective());
    }

    @Test
    @DisplayName("the ledger counts potions, torches, rooms and fights from the log")
    void ledgerMatchesTheLog() {
        var log = new EventLog();
        var engine = started(log, 20, 1, 18, 4);
        engine.useItem("fighter", Consumable.POTION);
        engine.tickClock(ClockId.LIGHT);
        engine.useItem("fighter", Consumable.TORCH);
        engine.crossExit("door-north");
        engine.crossExit("door-south");
        engine.spawnGoblin(6, 6);
        var goblin = engine.state().find("goblin").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), goblin.withHp(1)));
        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        engine.combat().moveTo("fighter", 6, 5, sink);
        engine.combat().attack("fighter", "goblin", sink);

        engine.crossExit("stair-south");

        EndingReport report = engine.scene().ending();
        assertEquals(Ending.EXTRACTED_WITHOUT, report.ending());
        assertEquals(2, report.roomsEntered());
        assertEquals(2, report.roomsInSite());
        assertEquals(1, report.potionsUsed());
        assertEquals(2, report.potionsBrought());
        assertEquals(1, report.torchesUsed());
        assertEquals(2, report.torchesBrought());
        assertEquals(1, report.fights());
        assertNotNull(report.hurt());
    }

    @Test
    @DisplayName("PARTY_LOST while holding the reliquary is FELL_WITH_HIM")
    void lostHoldingFallsWithHim() {
        var log = new EventLog();
        var engine = siteStarted(log, 1, 20, 19, 4);
        toChapel(engine);
        engine.takeProp("reliquary");
        engine.spawnGoblin(6, 6);
        var fighter = engine.state().find("fighter").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), fighter.withHp(1)));
        engine.combat().start(new CombatSink.Buffer());
        engine.combat().runAutomaticTurns(new CombatSink.Buffer(), () -> {});

        EndingReport report = engine.scene().ending();
        assertEquals(Ending.PARTY_LOST, report.ending());
        assertEquals(ObjectiveFate.FELL_WITH_HIM, report.objective());
        assertNull(report.hurt());
    }

    private static GameEngine lost(EventLog log) {
        var engine = started(log, 1, 20, 19, 4);
        engine.spawnGoblin(6, 6);
        var fighter = engine.state().find("fighter").orElseThrow();
        log.append(new Event.EntitySpawned(Instant.now(), fighter.withHp(1)));
        engine.combat().start(new CombatSink.Buffer());
        engine.combat().runAutomaticTurns(new CombatSink.Buffer(), () -> {});
        return engine;
    }
}
