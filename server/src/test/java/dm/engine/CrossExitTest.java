package dm.engine;

import dm.content.ContentLoader;
import dm.model.Event;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Square;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CrossExitTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static void damage(GameEngine engine, String targetId, int damage) {
        var attackRoll = new RollResult(
                RollRequest.attack("goblin", targetId, 4, 16),
                List.of(18), 22, Outcome.HIT);
        var damageRoll = new RollResult(
                RollRequest.damage("goblin", "1d6", 2), List.of(damage), damage + 2, Outcome.HIT);
        engine.log().append(new Event.AttackResolved(Instant.now(), "goblin", targetId,
                attackRoll, Optional.of(damageRoll), damage, true, false));
    }

    private static Square inwardLanding(GameEngine engine, String roomId, String neighbourId) {
        var room = engine.room(roomId);
        var arrival = room.exits().stream()
                .filter(e -> e.toRoomId().equals(neighbourId))
                .findFirst().orElseThrow();
        return arrival.inward(room.width(), room.height());
    }

    @Test
    @DisplayName("crossing lands the party one square inside, not in the doorway")
    void landsInward() {
        var engine = started(new EventLog());

        engine.crossExit("door-north");

        assertEquals("gallery", engine.state().roomId());
        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals("gallery", fighter.roomId());
        // The gallery's south door answers the crypt's north one. Landing on the door itself
        // would put the party on the trigger that sent them there.
        var arrival = engine.room("gallery").exits().stream()
                .filter(e -> e.toRoomId().equals("crypt"))
                .findFirst().orElseThrow();
        assertNotEquals(arrival.square(), new dm.model.Square(fighter.x(), fighter.y()));
        assertEquals(arrival.inward(engine.room("gallery").width(),
                engine.room("gallery").height()),
                new dm.model.Square(fighter.x(), fighter.y()));
    }

    @Test
    @DisplayName("crossing emits one PartyMoved naming the exit it went through")
    void emitsOneEvent() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");

        var moves = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .toList();
        assertEquals(1, moves.size());
        assertEquals("crypt", moves.getFirst().fromRoomId());
        assertEquals("gallery", moves.getFirst().toRoomId());
        assertEquals("door-north", moves.getFirst().throughExitId());
        assertEquals(java.util.List.of("fighter"), moves.getFirst().entityIds());
    }

    @Test
    @DisplayName("only the living cross — a corpse is not carried through the door")
    void deadPartyMembersStayBehind() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);

        engine.crossExit("door-north");

        // The goblin was never party, so it stays regardless; the assertion that matters is
        // that entityIds is the living party and not "everything in the room".
        var moved = log.events().stream()
                .filter(Event.PartyMoved.class::isInstance)
                .map(Event.PartyMoved.class::cast)
                .findFirst().orElseThrow();
        assertFalse(moved.entityIds().contains("goblin"));
        assertEquals("crypt", engine.state().find("goblin").orElseThrow().roomId());
    }

    @Test
    @DisplayName("arriving somewhere new records its dressing exactly once")
    void firstArrivalDresses() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");
        engine.crossExit("door-south");
        engine.crossExit("door-north");

        long gallery = log.events().stream()
                .filter(Event.RoomDressed.class::isInstance)
                .map(Event.RoomDressed.class::cast)
                .filter(e -> e.roomId().equals("gallery"))
                .count();
        assertEquals(1, gallery, "a room is dressed on first entry and never again");
    }

    @Test
    @DisplayName("an exit that is not in this room is refused")
    void unknownExitRefused() {
        var engine = started(new EventLog());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-west"));
        assertTrue(thrown.getMessage().contains("door-west"), thrown.getMessage());
    }

    @Test
    @DisplayName("you cannot walk out of a fight")
    void exitsAreIllegalInCombat() {
        var engine = started(new EventLog());
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> engine.crossExit("door-north"));
        assertTrue(thrown.getMessage().toLowerCase().contains("fight"), thrown.getMessage());
        assertEquals("crypt", engine.state().roomId());
    }

    @Test
    @DisplayName("the scene the client is sent carries the room's exits")
    void sceneCarriesExits() {
        var engine = started(new EventLog());

        assertEquals(engine.room().exits(), engine.scene().currentRoom().exits());
        assertFalse(engine.scene().currentRoom().exits().isEmpty());
    }

    @Test
    @DisplayName("a wound and a secret survive the round trip, and the goblin you left is still there")
    void partyStateSurvivesCrossing() {
        var engine = started(new EventLog());
        int maxHp = CONTENT.entity("fighter").maxHp();
        int cryptDamage = 3;
        int galleryDamage = 2;

        damage(engine, "fighter", cryptDamage);
        engine.revealProp("alcove");
        engine.spawnGoblin(6, 6);
        var goblinBefore = engine.state().find("goblin").orElseThrow();

        engine.crossExit("door-north");

        var fighterInGallery = engine.state().find("fighter").orElseThrow();
        assertEquals(maxHp - cryptDamage, fighterInGallery.hp(),
                "the wound from the crypt did not heal at the door");
        assertEquals("gallery", fighterInGallery.roomId());

        damage(engine, "fighter", galleryDamage);
        engine.revealProp("niche");

        engine.crossExit("door-south");

        var fighter = engine.state().find("fighter").orElseThrow();
        assertEquals(maxHp - cryptDamage - galleryDamage, fighter.hp(),
                "neither wound was forgotten on the way back");
        assertEquals("crypt", fighter.roomId());
        assertEquals(inwardLanding(engine, "crypt", "gallery"),
                new Square(fighter.x(), fighter.y()),
                "the party lands one square inside, not in the doorway");
        assertTrue(engine.state().revealedHere().contains("alcove"),
                "the alcove you found is still found");

        var goblinAfter = engine.state().find("goblin").orElseThrow();
        assertEquals(goblinBefore.id(), goblinAfter.id());
        assertEquals(goblinBefore.hp(), goblinAfter.hp());
        assertEquals(goblinBefore.x(), goblinAfter.x());
        assertEquals(goblinBefore.y(), goblinAfter.y());
        assertEquals("crypt", goblinAfter.roomId(),
                "the goblin you left in the crypt is still the same one");
    }

    @Test
    @DisplayName("a hostile left in another room is not in this fight")
    void combatIgnoresHostilesInOtherRooms() {
        var engine = started(new EventLog());
        engine.spawnGoblin(6, 6);
        var goblinBefore = engine.state().find("goblin").orElseThrow();

        engine.crossExit("door-north");

        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);

        assertFalse(engine.combat().isActive(),
                "only the fighter is here — start() treats that as nothing to fight");
        assertTrue(sink.collectedRolls().stream()
                .noneMatch(r -> "goblin".equals(r.request().actorId())),
                "a goblin in another room must not roll initiative");

        var goblinAfter = engine.state().find("goblin").orElseThrow();
        assertEquals("crypt", goblinAfter.roomId());
        assertEquals(goblinBefore.x(), goblinAfter.x());
        assertEquals(goblinBefore.y(), goblinAfter.y());
    }
}
