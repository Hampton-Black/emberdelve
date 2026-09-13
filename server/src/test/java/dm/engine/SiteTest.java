package dm.engine;

import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.model.ConsequenceId;
import dm.model.Diff;
import dm.model.Ending;
import dm.model.Event;
import dm.model.LightingPreset;
import dm.model.PropType;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.8: five authored rooms around the crypt and gallery, objective shallow,
 * greed prize deeper, door copy that matches {@code crossExit}.
 */
class SiteTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final String[] SITE = {
            "crypt", "gallery", "chapel", "undercroft", "vault"};

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10),
                Rooms.authored(CONTENT, SITE),
                options -> ConsequenceId.IT_PASSES_BY);
        engine.start();
        return engine;
    }

    private static void walk(GameEngine engine, String... exitIds) {
        for (var id : exitIds) {
            engine.crossExit(id);
        }
    }

    @Test
    @DisplayName("the site is five rooms and every exit lands in a loaded room except the way out")
    void fiveRoomsAndEveryExitLands() {
        var rooms = Rooms.authored(CONTENT, SITE);
        assertEquals(5, rooms.size());
        for (var roomId : SITE) {
            assertTrue(rooms.has(roomId), roomId);
        }

        for (var roomId : SITE) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                if (exit.wayOut()) {
                    assertEquals("crypt", roomId, "only the entrance is a way out");
                    assertEquals("stair-south", exit.id());
                    continue;
                }
                assertTrue(rooms.has(exit.toRoomId()),
                        roomId + " " + exit.id() + " leads to unloaded " + exit.toRoomId());
                var other = CONTENT.room(exit.toRoomId());
                var back = other.exits().stream()
                        .filter(e -> e.toRoomId().equals(roomId))
                        .filter(e -> e.direction() == exit.direction().opposite())
                        .findFirst();
                assertTrue(back.isPresent(),
                        roomId + " " + exit.direction() + " to " + other.roomId()
                                + " has no answering door");
            }
        }
    }

    @Test
    @DisplayName("gallery still crosses; crypt south extracts")
    void galleryCrossesAndCryptSouthExtracts() {
        var log = new EventLog();
        var engine = started(log);

        engine.crossExit("door-north");
        assertEquals("gallery", engine.state().roomId());
        assertTrue(engine.state().ending().isEmpty());

        engine.crossExit("door-south");
        assertEquals("crypt", engine.state().roomId());

        engine.crossExit("stair-south");
        assertEquals(Ending.EXTRACTED_WITHOUT, engine.state().ending().orElseThrow());
    }

    @Test
    @DisplayName("the reliquary lives in the chapel, not the crypt")
    void reliquaryIsInChapelNotCrypt() {
        assertThrows(IllegalArgumentException.class, () -> CONTENT.room("crypt").prop("reliquary"));
        var reliquary = CONTENT.room("chapel").prop("reliquary");
        assertEquals(PropType.CONTAINER, reliquary.type());
        assertEquals(List.of("take"), reliquary.actions());
    }

    @Test
    @DisplayName("taking the reliquary holds it; taking the vault prize does not")
    void objectiveHoldsAndPrizeDoesNot() {
        var log = new EventLog();
        var engine = started(log);

        walk(engine, "door-north", "door-north");
        assertEquals("chapel", engine.state().roomId());

        engine.takeProp("reliquary");
        assertTrue(engine.state().holdingObjective());
        assertTrue(engine.scene().holdingObjective());
        assertTrue(engine.scene().currentRoom().props().stream()
                .noneMatch(p -> p.id().equals("reliquary")));
        assertEquals(1, eventsOf(log, Event.ObjectiveTaken.class).size());
        assertEquals("chapel", eventsOf(log, Event.ObjectiveTaken.class).getFirst().roomId());

        walk(engine, "door-north", "door-north");
        assertEquals("vault", engine.state().roomId());

        engine.takeProp("gold-chest");
        assertTrue(engine.state().holdingObjective(), "already holding the reliquary");
        assertTrue(engine.scene().currentRoom().props().stream()
                .noneMatch(p -> p.id().equals("gold-chest")),
                "the prize leaves the visible list");
        assertTrue(engine.state().takenHere().contains("gold-chest"));
        assertEquals(1, eventsOf(log, Event.ObjectiveTaken.class).size(),
                "the prize is not a second objective");
        assertEquals(1, eventsOf(log, Event.PropTaken.class).size());
        var prize = eventsOf(log, Event.PropTaken.class).getFirst();
        assertEquals("vault", prize.roomId());
        assertEquals("gold-chest", prize.propId());
    }

    @Test
    @DisplayName("taking only the vault prize does not hold the objective, and the way out still checks the reliquary")
    void prizeAloneDoesNotExtractWithObjective() {
        var log = new EventLog();
        var engine = started(log);

        walk(engine, "door-north", "door-north", "door-north", "door-north");
        var diffs = engine.takeProp("gold-chest");

        assertFalse(engine.state().holdingObjective());
        assertFalse(engine.scene().holdingObjective());
        assertTrue(engine.scene().currentRoom().props().stream()
                .noneMatch(p -> p.id().equals("gold-chest")));
        assertTrue(eventsOf(log, Event.ObjectiveTaken.class).isEmpty());
        assertEquals(1, eventsOf(log, Event.PropTaken.class).size());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.PropRemoved removed
                && removed.propId().equals("gold-chest")
                && !removed.holdingObjective()));

        walk(engine, "door-south", "door-south", "door-south", "door-south");
        engine.crossExit("stair-south");
        assertEquals(Ending.EXTRACTED_WITHOUT, engine.state().ending().orElseThrow());
    }

    @Test
    @DisplayName("at least one room's fires move brighter than its initial lighting")
    void oneRoomMovesBrighter() {
        boolean brighter = false;
        for (var roomId : SITE) {
            var room = CONTENT.room(roomId);
            var fires = room.fires();
            if (fires == null) {
                continue;
            }
            if (gloom(fires.to()) < gloom(room.lighting())) {
                brighter = true;
            }
        }
        assertTrue(brighter, "ffi.9: at least one room must move BRIGHTER");
        var crypt = CONTENT.room("crypt");
        assertEquals(LightingPreset.DARK, crypt.fires().to(), "crypt stays the DARKER example");
        assertTrue(gloom(crypt.fires().to()) > gloom(crypt.lighting()));
    }

    @Test
    @DisplayName("door descriptions and dmNotes do not claim a lock, a missing handle, or a dead end")
    void doorCopyMatchesTheEngine() {
        for (var roomId : SITE) {
            var room = CONTENT.room(roomId);
            for (var prop : room.props()) {
                if (prop.type() != PropType.DOOR) {
                    continue;
                }
                assertNoLie(roomId + " " + prop.id(), prop.description());
            }
            var notes = room.dmNotes();
            assertNoLie(roomId + " overview", notes.overview());
            assertNoLie(roomId + " sensory", notes.sensory());
            if (notes.theDoor() != null) {
                assertNoLie(roomId + " theDoor", notes.theDoor());
            }
        }
    }

    @Test
    @DisplayName("App loads five rooms; the ending ledger roomsInSite is 5")
    void appSiteHasFiveRoomsOnTheLedger() throws Exception {
        var app = Files.readString(Path.of("src/main/java/dm/App.java"));
        assertTrue(app.contains(
                        "Rooms.authored(content, \"crypt\", \"gallery\", \"chapel\", \"undercroft\", \"vault\")"),
                "App must boot the five-room site");

        var rooms = Rooms.authored(CONTENT, SITE);
        assertEquals(5, rooms.size());
        var engine = started(new EventLog());
        assertEquals(5, engine.rooms().size());
        engine.crossExit("stair-south");
        assertEquals(5, engine.scene().ending().roomsInSite());
    }

    @Test
    @DisplayName("SCHEMA_VERSION stays 3")
    void schemaVersionUnchanged() {
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("chapel, undercroft and vault have goblin spawn slots, and no second goblin is authored")
    void encounterSlotsWithoutASecondGoblin() {
        for (var roomId : List.of("chapel", "undercroft", "vault")) {
            var spawn = CONTENT.room(roomId).startPositions().goblinSpawn();
            assertNotNull(spawn, roomId + " needs a goblinSpawn for 4h9.9");
        }
        assertEquals("goblin", CONTENT.room("crypt").prop("sarcophagus").contains());
        for (var roomId : SITE) {
            for (var prop : CONTENT.room(roomId).props()) {
                if ("sarcophagus".equals(prop.id()) && "crypt".equals(roomId)) {
                    continue;
                }
                assertFalse("goblin".equals(prop.contains()),
                        roomId + " " + prop.id() + " must not author a second goblin");
            }
        }
    }

    @Test
    @DisplayName("new rooms dress from at least two kits; hidden props hint on something a player looks at")
    void newRoomsSpendKitsAndHintWherePlayersLook() {
        for (var roomId : List.of("chapel", "undercroft", "vault")) {
            var room = CONTENT.room(roomId);
            var kits = room.props().stream()
                    .map(RoomDefinition.PropDefinition::appearance)
                    .filter(a -> a != null && !a.isBlank())
                    .map(a -> dm.model.Appearances.spec(a).kit())
                    .collect(Collectors.toSet());
            assertTrue(kits.size() >= 2, roomId + " kits: " + kits);
            assertFalse(room.hiddenPropIds().isEmpty(), roomId + " needs something to find");
            for (var id : room.hiddenPropIds()) {
                assertHintWherePlayersLook(roomId, room.prop(id));
            }
        }

        var gallery = CONTENT.room("gallery");
        assertFalse(gallery.hiddenPropIds().isEmpty(), "gallery needs something to find");
        for (var id : gallery.hiddenPropIds()) {
            assertHintWherePlayersLook("gallery", gallery.prop(id));
        }
    }

    private static void assertHintWherePlayersLook(String roomId, RoomDefinition.PropDefinition prop) {
        var id = roomId + " " + prop.id();
        assertNotNull(prop.revealHint(), id);
        assertFalse(prop.revealHint().isBlank(), id);
        if ("gallery".equals(roomId) && "niche".equals(prop.id())) {
            var lower = prop.revealHint().toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("west wall"), id + " hints on the west wall: " + prop.revealHint());
            assertFalse(lower.contains("tiling"), id + " hints on tiling: " + prop.revealHint());
            assertFalse(lower.contains("grout"), id + " hints on grout: " + prop.revealHint());
            assertTrue(lower.contains("brazier") || lower.contains("pillar"),
                    id + " must hint on the brazier or pillars: " + prop.revealHint());
        }
    }

    private static void assertNoLie(String where, String text) {
        var lower = text.toLowerCase(Locale.ROOT);
        assertFalse(lower.contains("no handle"), where + " claims a missing handle: " + text);
        assertFalse(lower.contains("dead end"), where + " claims a dead end: " + text);
        assertFalse(lower.contains("lock"), where + " claims a lock: " + text);
        assertFalse(lower.contains("sealed"), where + " claims sealed: " + text);
        assertFalse(lower.contains("cannot open") || lower.contains("will not open")
                        || lower.contains("won't open"),
                where + " claims the door cannot open: " + text);
    }

    private static int gloom(LightingPreset preset) {
        return switch (preset) {
            case TORCHLIT -> 0;
            case BRAZIERLIT -> 1;
            case DIM -> 2;
            case DARK -> 3;
        };
    }

    private static <T extends Event> List<T> eventsOf(EventLog log, Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }
}
