package dm.ai;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedDiceRoller;
import dm.model.Directive;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec §7a: the player can see there is a door and cannot see what is behind it. A destination
 * id in the prompt is a string the narrator can read aloud — m2-evaluation §8's grid-coordinate
 * finding in a different costume.
 */
class ThresholdContextTest {

    private static final ContentLoader CONTENT = new ContentLoader();

    @Test
    @DisplayName("ways out name the wall, never the room on the other side")
    void waysOutNameDirections() {
        var block = DmService.waysOut(CONTENT.room("crypt"));

        assertTrue(block.contains("north"), block);
        assertFalse(block.contains("gallery"), "the destination is not the player's to know");
        assertFalse(block.contains("door-north"), "nor is the id");
    }

    @Test
    @DisplayName("ways out say they are open, because the engine has no way for them not to be")
    void waysOutStatePassability() {
        var block = DmService.waysOut(CONTENT.room("crypt"));

        // crossExit refuses exactly one thing. Listing a way out without saying so left the
        // model to reconcile "no handle on this side" against a bare line saying a way out
        // exists, and it decided differently every turn — inventing a DC 20 kick for a door
        // that was never shut. See emberdelve-xgg.10.
        assertTrue(block.contains("locked"), block);
        assertTrue(block.contains("fight"), "the one case where they cannot leave");
    }

    @Test
    @DisplayName("no authored door claims to be shut, which nothing can make true")
    void authoredDoorsDoNotClaimToBeShut() {
        for (var roomId : java.util.List.of("crypt", "gallery", "chapel", "undercroft", "vault")) {
            var room = CONTENT.room(roomId);
            for (var exit : room.exits()) {
                var door = room.props().stream()
                        .filter(prop -> prop.id().equals(exit.id()))
                        .findFirst()
                        .orElseThrow();
                assertFalse(door.description().toLowerCase().contains("shut"),
                        roomId + "'s " + door.id() + " says it is shut, and no exit ever is");
            }
        }
    }

    @Test
    @DisplayName("a room with no way out says so rather than printing an empty heading")
    void noExitsNoHeading() {
        var sealed = new dm.content.RoomDefinition(
                "sealed", "A Sealed Room", 4, 4,
                dm.model.FloorType.STONE, dm.model.WallType.STONE,
                dm.model.LightingPreset.DARK,
                java.util.List.of(), java.util.List.of(),
                new dm.content.RoomDefinition.StartPositions(
                        java.util.List.of(new dm.content.RoomDefinition.Point(1, 1)),
                        new dm.content.RoomDefinition.Point(2, 2)),
                new dm.content.RoomDefinition.DmNotes("o", "s", null, null, null));

        // An empty "## Ways out" heading is an invitation to invent one — the same reason
        // ToolSchema does not offer reveal_prop with an empty enum.
        assertEquals("", DmService.waysOut(sealed));
    }

    @Test
    @DisplayName("the arrival directive forks on whether the party has been here before")
    void arrivalForksOnVisits() {
        assertNotEquals(DmService.ARRIVAL_FIRST, DmService.ARRIVAL_RETURN);
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("been here"),
            DmService.ARRIVAL_RETURN);
        // The window will have dropped the first visit; "do not rebuild it from scratch" is the
        // whole point of telling it. Spec §7b.
        assertTrue(DmService.ARRIVAL_RETURN.toLowerCase().contains("not")
                        && DmService.ARRIVAL_RETURN.toLowerCase().contains("again"),
                DmService.ARRIVAL_RETURN);
    }

    @Test
    @DisplayName("the threshold line says a seam happened, and names neither room's contents")
    void thresholdMarkerMarksTheSeam() {
        var line = DmService.thresholdMarker("The Ashen Crypt", "The Long Gallery");

        assertTrue(line.contains("The Ashen Crypt"), line);
        assertTrue(line.contains("The Long Gallery"), line);
        // Bleed is the failure: room 1's tallies written onto room 2's stone, because the
        // six-turn window is still full of room 1. Spec §7b.
        assertTrue(line.toLowerCase().contains("different room")
                        || line.toLowerCase().contains("somewhere else"), line);
    }

    @Test
    @DisplayName("a click crossing marks the seam and defers arrival to the next typed turn")
    void noteCrossingDefersArrivalToNextProsePhase() {
        var log = new EventLog();
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();

        var tools = new ScriptedDmClient();
        var prose = new ScriptedDmClient("Cold stone underfoot.");
        var dm = new DmService(tools, prose, engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));

        dm.noteCrossing("The Ashen Crypt", "The Long Gallery", false);
        dm.handleFreeText("fighter", "I look around", new TurnSink() {
            @Override public void narration(dm.model.NarrationSegment segment) {}
            @Override public void diffs(java.util.List<dm.model.Diff> diffs) {}
            @Override public void roll(dm.model.RollResult roll) {}
            @Override public void error(Throwable error) {}
            @Override public void complete() {}
        });

        var conversation = prose.conversations().getLast();
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
    @DisplayName("two party signs and an arrival all survive to the next typed turn, in order")
    void signsAndArrivalDrainInOrder() {
        var engine = engineForRail();
        var prose = new ScriptedDmClient("Cold stone underfoot.");
        var dm = dm(engine, prose);

        engine.directives().latch(Directive.aboutParty("The ring of light has drawn in."));
        engine.directives().latch(Directive.aboutParty("Far off, something moved and went quiet."));
        dm.noteCrossing("The Ashen Crypt", "The Long Gallery", false);
        dm.handleFreeText("fighter", "I look around", silentSink());

        var directive = proseDirective(prose);
        int light = directive.indexOf("The ring of light has drawn in.");
        int stirred = directive.indexOf("Far off, something moved and went quiet.");
        int arrival = directive.indexOf(DmService.ARRIVAL_FIRST);
        assertTrue(light >= 0 && stirred > light && arrival > stirred, directive);
    }

    @Test
    @DisplayName("a second crossing drops the previous arrival and keeps party-scoped signs")
    void secondCrossingDropsArrivalKeepsSigns() {
        var engine = engineForRail();
        var dm = dm(engine, new ScriptedDmClient("x"));

        engine.directives().latch(Directive.aboutParty("The ring of light has drawn in."));
        engine.crossExit("door-north");
        dm.noteCrossing("The Ashen Crypt", "The Long Gallery", false);

        engine.crossExit("door-south");
        dm.noteCrossing("The Long Gallery", "The Ashen Crypt", true);

        var waiting = engine.directives().snapshot().stream()
                .map(Directive::clause)
                .toList();
        assertTrue(waiting.stream().anyMatch(c -> c.contains("ring of light")), waiting.toString());
        assertTrue(waiting.stream().anyMatch(c -> c.contains(DmService.ARRIVAL_RETURN)),
                waiting.toString());
        assertTrue(waiting.stream().noneMatch(c -> c.contains(DmService.ARRIVAL_FIRST)
                && !c.contains("been here")), waiting.toString());
    }

    private static GameEngine engineForRail() {
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

    private static String proseDirective(ScriptedDmClient prose) {
        return prose.conversations().getLast().stream()
                .filter(m -> "user".equals(m.role()))
                .map(DmClient.ChatMessage::content)
                .filter(c -> c != null && c.contains("Three sentences at most."))
                .findFirst()
                .orElseThrow();
    }
}
