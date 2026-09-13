package dm.ai;

import dm.ScriptedDmClient;
import dm.content.ContentLoader;
import dm.engine.GameEngine;
import dm.engine.Rooms;
import dm.engine.ScriptedClockDraw;
import dm.engine.ScriptedDiceRoller;
import dm.model.ClockId;
import dm.model.ConsequenceId;
import dm.model.Event;
import dm.model.Outcome;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.14: the projection carries hurt, clocks, the objective, and consumables
 * as fiction that survives a crossing. Spec §8b.
 */
class ProjectionTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final Instant T = Instant.now();
    private static final Pattern HEADING = Pattern.compile("^## .+$", Pattern.MULTILINE);
    private static final Pattern HP_FRACTION = Pattern.compile("\\d+/\\d+");
    private static final Pattern COORD = Pattern.compile("\\(\\d+,\\d+\\)");
    private static final Pattern HP_WORD = Pattern.compile("(?i)\\bhp\\b");

    private static GameEngine started() {
        return started(new EventLog());
    }

    private static GameEngine started(EventLog log) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"));
        engine.start();
        return engine;
    }

    private static DmService dm(GameEngine engine) {
        return new DmService(new ScriptedDmClient(), new ScriptedDmClient("x"), engine,
                CONTENT.prompt("dm-tools"), CONTENT.prompt("dm"), CONTENT.prompt("dm-reconcile"));
    }

    private static String projection(GameEngine engine) {
        return dm(engine).worldState(false);
    }

    private static String section(String text, String heading) {
        int start = text.indexOf(heading);
        assertTrue(start >= 0, "missing " + heading + " in:\n" + text);
        int next = text.indexOf("\n## ", start + heading.length());
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    private static List<String> headings(String text) {
        return HEADING.matcher(text).results().map(r -> r.group()).toList();
    }

    private static void damageFighterTo(EventLog log, GameEngine engine, int hp) {
        int damage = engine.state().find("fighter").orElseThrow().hp() - hp;
        var attackRoll = new dm.model.RollResult(
                dm.model.RollRequest.attack("goblin", "fighter", 4, 16),
                List.of(18), 22, Outcome.HIT);
        var damageRoll = new dm.model.RollResult(
                dm.model.RollRequest.damage("goblin", "1d6", 2), List.of(damage), damage + 2,
                Outcome.HIT);
        log.append(new Event.AttackResolved(T, "goblin", "fighter",
                attackRoll, java.util.Optional.of(damageRoll), damage, true, false));
    }

    @Test
    @DisplayName("a fresh crypt projects the party above entities, with torch full and the site quiet")
    void freshCryptCarriesThePartyAndTheLitFires() {
        var text = projection(started());

        var heads = headings(text);
        int party = heads.indexOf("## The party");
        int entities = heads.indexOf("## Entities present");
        assertTrue(party >= 0 && entities == party + 1, heads.toString());
        assertEquals(List.of(
                "## Room: The Ashen Crypt",
                "## What the player can see",
                "## Hidden — the player cannot see these yet",
                "## Secrets you know and the player does not",
                "## The party",
                "## Entities present",
                "## Ways out",
                "## Grid"), heads);

        var partyBlock = section(text, "## The party");
        assertTrue(partyBlock.contains("Session state. It followed them in here and it will follow them out."));
        assertTrue(partyBlock.contains("- `fighter` — Roderick, barely marked, at (6,1)"));
        assertFalse(partyBlock.contains("hp"), partyBlock);
        assertTrue(partyBlock.contains("- objective: not yet found"));
        assertTrue(partyBlock.contains("- torch: full"));
        assertTrue(partyBlock.contains("- the site: quiet"));
        assertTrue(partyBlock.contains("- potions: 2 · torches: 2 · rope: 0"));
        assertTrue(partyBlock.contains("There is nothing else here worth carrying out. Do not invent a second."));
        assertTrue(partyBlock.contains("The torch and anything moving toward the party are the engine's to change, not yours."));
        assertTrue(partyBlock.contains("Narrate what you are told has happened. Do not decide that the light goes out."));
        assertFalse(partyBlock.toLowerCase().contains("lantern"), partyBlock);

        var entitiesBlock = section(text, "## Entities present");
        assertTrue(entitiesBlock.contains("None."), entitiesBlock);
        assertFalse(entitiesBlock.contains("`fighter`"), entitiesBlock);
        assertFalse(entitiesBlock.contains("[the player]"), entitiesBlock);
        assertFalse(HP_FRACTION.matcher(entitiesBlock).find(), entitiesBlock);

        String overview = CONTENT.room("crypt").dmNotes().overview();
        assertFalse(overview.contains("lit green"), overview);
        assertFalse(overview.contains("braziers that should have burned"), overview);
        assertFalse(text.contains(overview) && overview.contains("Lit green"), text);

        String sensory = CONTENT.room("crypt").dmNotes().sensory();
        String lit = CONTENT.room("crypt").fires().lit();
        int afterSensory = text.indexOf(sensory) + sensory.length();
        int fireAt = text.indexOf(lit, afterSensory);
        int visible = text.indexOf("## What the player can see");
        assertTrue(fireAt > afterSensory && fireAt < visible,
                "the lit fire line belongs after sensory, before what the player can see:\n" + text);
    }

    @Test
    @DisplayName("a wounded fighter uses the same hurt band in the party line and in a combat beat")
    void hurtBandIsSharedWithCombatBeats() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        damageFighterTo(log, engine, 10);

        var fighter = engine.state().find("fighter").orElseThrow();
        String band = BeatRenderer.condition(fighter);
        assertEquals("bloodied", band);

        var partyLine = section(projection(engine), "## The party");
        assertTrue(partyLine.contains("- `fighter` — Roderick, bloodied, at (6,1)"), partyLine);

        var beat = BeatRenderer.render(engine.state(), new Event.AttackResolved(T, "goblin",
                "fighter",
                new dm.model.RollResult(dm.model.RollRequest.attack("goblin", "fighter", 4, 16),
                        List.of(18), 22, Outcome.HIT),
                java.util.Optional.empty(), 4, true, false));
        assertTrue(beat.contains(band), beat);
    }

    @Test
    @DisplayName("LIGHT at 4 is guttering and ALERT at 2 is stirring")
    void clockBandsFollowFilled() {
        var engine = started();
        for (int i = 0; i < 4; i++) {
            engine.tickClock(ClockId.LIGHT);
        }
        engine.tickClock(ClockId.ALERT);
        engine.tickClock(ClockId.ALERT);

        var party = section(projection(engine), "## The party");
        assertTrue(party.contains("- torch: guttering"), party);
        assertTrue(party.contains("- the site: stirring"), party);
    }

    @Test
    @DisplayName("the party block follows a crossing; the gallery has no fire line")
    void partyBlockSurvivesACrossing() {
        var log = new EventLog();
        var engine = started(log);
        damageFighterTo(log, engine, 10);

        var before = section(projection(engine), "## The party");
        assertTrue(before.contains("bloodied"), before);
        assertTrue(before.contains("- torch: full"), before);
        assertTrue(before.contains("- the site: quiet"), before);
        assertTrue(before.contains("- potions: 2 · torches: 2 · rope: 0"), before);

        engine.crossExit("door-north");

        var text = projection(engine);
        var after = section(text, "## The party");
        assertTrue(after.contains("bloodied"), after);
        assertTrue(after.contains("- torch: full"), after);
        assertTrue(after.contains("- the site: quiet"), after);
        assertTrue(after.contains("- potions: 2 · torches: 2 · rope: 0"), after);
        assertFalse(text.contains(CONTENT.room("crypt").fires().lit()), text);
        assertFalse(text.contains(CONTENT.room("crypt").fires().moved()), text);
        assertTrue(text.contains("## Room: The Long Gallery"), text);
    }

    @Test
    @DisplayName("moved crypt fires replace the lit line once lightingIn is present")
    void movedFiresReplaceTheLitLine() {
        var engine = new GameEngine(CONTENT, new EventLog(),
                new ScriptedDiceRoller(10, 10, 10, 10),
                Rooms.authored(CONTENT, "crypt", "gallery"),
                new ScriptedClockDraw(ConsequenceId.IT_PASSES_BY));
        engine.start();
        for (int i = 0; i < 6; i++) {
            engine.tickClock(ClockId.ALERT);
        }

        var text = projection(engine);
        String lit = CONTENT.room("crypt").fires().lit();
        String moved = CONTENT.room("crypt").fires().moved();
        String sensory = CONTENT.room("crypt").dmNotes().sensory();
        int afterSensory = text.indexOf(sensory) + sensory.length();
        int movedAt = text.indexOf(moved, afterSensory);
        int visible = text.indexOf("## What the player can see");
        assertTrue(movedAt > afterSensory && movedAt < visible, text);
        assertFalse(text.contains(lit), text);
        assertTrue(engine.state().lightingIn("crypt").isPresent());
    }

    @Test
    @DisplayName("entities present says None until a hostile arrives")
    void entitiesPresentSaysNoneUntilSpawned() {
        var engine = started();
        var empty = section(projection(engine), "## Entities present");
        assertTrue(empty.contains("None."), empty);
        assertFalse(empty.contains("`goblin`"), empty);

        engine.spawnGoblin(6, 6);
        var withGoblin = section(projection(engine), "## Entities present");
        assertFalse(withGoblin.contains("None"), withGoblin);
        assertTrue(withGoblin.contains("`goblin`"), withGoblin);
    }

    @Test
    @DisplayName("no hp fraction in party or entities, and no extra coordinates in the party block")
    void noHpFractionsOrStrayCoordinates() {
        var log = new EventLog();
        var engine = started(log);
        engine.spawnGoblin(6, 6);
        damageFighterTo(log, engine, 10);

        var text = projection(engine);
        var party = section(text, "## The party");
        var entities = section(text, "## Entities present");

        assertFalse(HP_FRACTION.matcher(party).find(), party);
        assertFalse(HP_FRACTION.matcher(entities).find(), entities);
        assertFalse(HP_WORD.matcher(party).find(), party);
        assertFalse(HP_WORD.matcher(entities).find(), entities);

        Matcher coords = COORD.matcher(party);
        while (coords.find()) {
            int lineStart = party.lastIndexOf('\n', coords.start()) + 1;
            int lineEnd = party.indexOf('\n', coords.start());
            String line = party.substring(lineStart, lineEnd < 0 ? party.length() : lineEnd);
            assertTrue(line.matches("- `[^`]+` — .+, .+, at \\(\\d+,\\d+\\)"),
                    "coordinate outside a party line: " + line);
        }
        assertTrue(entities.contains("`goblin`"), entities);
        assertFalse(entities.contains("`fighter`"), entities);
    }
}
