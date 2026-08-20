package dm;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Mode;
import dm.model.RollPurpose;
import dm.model.Square;
import dm.repo.InMemoryGameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attack resolution and the legality rules the client is not allowed to compute for itself.
 *
 * <p>Every roll here comes from a scripted die, so a failure means the engine changed and not
 * that the test got unlucky — the reason {@code DiceRoller} is injected rather than static.
 */
class CombatEngineTest {

    /** Fighter: AC 16, 12 hp, +5 to hit, 1d8+3, DEX +1. Goblin: AC 15, 7 hp, +4, 1d6+2, DEX +2. */
    private static Fixture fight(Integer... faces) {
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(new ContentLoader(), repo, new ScriptedDiceRoller(faces));
        engine.start();
        engine.spawnGoblin(6, 6);
        return new Fixture(engine, repo);
    }

    private record Fixture(GameEngine engine, InMemoryGameRepository repo) {
        CombatSink.Buffer start() {
            var sink = new CombatSink.Buffer();
            engine.combat().start(sink);
            return sink;
        }

        void place(String id, int x, int y) {
            repo.put(repo.find(id).orElseThrow().movedTo(x, y));
        }

        Entity get(String id) {
            return repo.find(id).orElseThrow();
        }
    }

    // ---- Initiative ----

    @Test
    @DisplayName("initiative sorts by total, highest first")
    void initiativeOrder() {
        // Rolled in id order: fighter 5+1=6, goblin 15+2=17.
        var fixture = fight(5, 15);
        fixture.start();

        var view = fixture.engine.combat().view();
        assertEquals(List.of("goblin", "fighter"),
                view.order().stream().map(dm.model.Combatant::entityId).toList());
        assertEquals("goblin", view.activeId());
        assertEquals(1, view.round());
    }

    @Test
    @DisplayName("a tie on the total is broken by the higher DEX, not by map order")
    void tiesBreakOnDexterity() {
        // fighter 10+1=11, goblin 9+2=11.
        var fixture = fight(10, 9);
        fixture.start();

        assertEquals("goblin", fixture.engine.combat().view().order().getFirst().entityId());
    }

    @Test
    @DisplayName("starting combat emits one initiative roll per combatant and switches mode")
    void startEmitsRollsAndMode() {
        var fixture = fight(12, 3);
        var sink = fixture.start();

        assertEquals(2, sink.collectedRolls().size());
        assertTrue(sink.collectedRolls().stream()
                .allMatch(r -> r.request().purpose() == RollPurpose.INITIATIVE));
        assertEquals(Mode.COMBAT, fixture.repo.mode());
        assertTrue(sink.collectedDiffs().stream().anyMatch(d -> d instanceof Diff.CombatChanged));
    }

    // ---- Attack resolution ----

    @Test
    @DisplayName("a hit applies damage and reports it as a stat change")
    void hitAppliesDamage() {
        // initiative 20/1 (fighter first), attack 14 (+5 = 19 vs AC 15), damage 4 (+3 = 7).
        var fixture = fight(20, 1, 14, 4);
        fixture.start();
        fixture.place("fighter", 6, 5);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        assertEquals(0, fixture.get("goblin").hp());
        assertTrue(sink.collectedDiffs().stream().anyMatch(d ->
                d instanceof Diff.StatChanged s && s.entityId().equals("goblin") && s.to() == 0));
    }

    @Test
    @DisplayName("a natural 20 doubles the damage dice but not the modifier")
    void critDoublesTheDice() {
        // initiative 20/1, attack 20 (crit), then TWO damage dice of 3 each: 3+3+3 modifier = 9.
        var fixture = fight(20, 1, 20, 3, 3);
        fixture.start();
        fixture.place("fighter", 6, 5);
        fixture.place("goblin", 6, 6);
        // A 12 hp target, so the crit's total is readable instead of clamping at zero.
        fixture.repo.put(fixture.get("goblin").withHp(12));

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        var damage = sink.collectedRolls().stream()
                .filter(r -> r.request().purpose() == RollPurpose.DAMAGE)
                .findFirst().orElseThrow();
        assertEquals(List.of(3, 3), damage.faces());
        assertEquals(9, damage.total());
    }

    @Test
    @DisplayName("a natural 1 misses regardless of the modifier, and rolls no damage")
    void naturalOneAlwaysMisses() {
        var fixture = fight(20, 1, 1, 6);
        fixture.start();
        fixture.place("fighter", 6, 5);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        assertEquals(7, fixture.get("goblin").hp());
        assertTrue(sink.collectedRolls().stream()
                .noneMatch(r -> r.request().purpose() == RollPurpose.DAMAGE));
    }

    @Test
    @DisplayName("killing the last hostile ends combat and returns to exploration")
    void lastKillEndsCombat() {
        var fixture = fight(20, 1, 18, 6);
        fixture.start();
        fixture.place("fighter", 6, 5);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        assertFalse(fixture.engine.combat().isActive());
        assertEquals(Mode.EXPLORATION, fixture.repo.mode());
        assertTrue(sink.collectedDiffs().stream()
                .anyMatch(d -> d instanceof Diff.CombatChanged c && c.combat() == null));
    }

    @Test
    @DisplayName("one attack per turn")
    void actionIsSpent() {
        var fixture = fight(20, 1, 2, 1, 2, 1);
        fixture.start();
        fixture.place("fighter", 6, 5);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        assertThrows(IllegalArgumentException.class,
                () -> fixture.engine.combat().attack("fighter", "goblin", sink));
    }

    @Test
    @DisplayName("a creature out of reach is not a legal target")
    void reachIsOneSquare() {
        var fixture = fight(20, 1);
        fixture.start();
        fixture.place("fighter", 6, 4);
        fixture.place("goblin", 6, 6);

        assertEquals(List.of(), fixture.engine.combat().view().legalTargets());
        assertThrows(IllegalArgumentException.class, () ->
                fixture.engine.combat().attack("fighter", "goblin", new CombatSink.Buffer()));
    }

    // ---- Legality the client is handed rather than computing ----

    @Test
    @DisplayName("legal moves reach exactly as far as speed allows")
    void movesStopAtSpeed() {
        var fixture = fight(20, 1);
        fixture.start();
        // The southern row is clear of props, so this measures speed and nothing else.
        fixture.place("fighter", 0, 0);
        fixture.place("goblin", 11, 11);

        var moves = fixture.engine.combat().view().legalMoves();

        assertTrue(moves.contains(new Square(6, 0)), "six squares is within a 30ft speed");
        assertFalse(moves.contains(new Square(7, 0)), "seven is not");
        assertFalse(moves.contains(new Square(0, 0)), "standing still is not a move");
    }

    @Test
    @DisplayName("solid props are not squares you can stand on")
    void propsBlock() {
        var fixture = fight(20, 1);
        fixture.start();
        fixture.place("fighter", 6, 5);
        fixture.place("goblin", 0, 0);

        var moves = fixture.engine.combat().view().legalMoves();

        assertFalse(moves.contains(new Square(6, 7)), "the sarcophagus is at (6,7)");
        assertFalse(moves.contains(new Square(3, 4)), "a pillar is at (3,4)");
        assertTrue(moves.contains(new Square(6, 6)), "the square in front of it is open");
    }

    @Test
    @DisplayName("another creature's square is not a legal move")
    void creaturesBlock() {
        var fixture = fight(20, 1);
        fixture.start();
        fixture.place("fighter", 6, 4);
        fixture.place("goblin", 6, 5);

        assertFalse(fixture.engine.combat().view().legalMoves().contains(new Square(6, 5)));
    }

    @Test
    @DisplayName("movement is spent, so a second move is shorter than the first")
    void movementIsSpent() {
        var fixture = fight(20, 1);
        fixture.start();
        fixture.place("fighter", 0, 0);
        fixture.place("goblin", 11, 11);

        fixture.engine.combat().moveTo("fighter", 4, 0, new CombatSink.Buffer());

        assertEquals(2, fixture.engine.combat().view().movementRemaining());
        assertThrows(IllegalArgumentException.class, () ->
                fixture.engine.combat().moveTo("fighter", 8, 0, new CombatSink.Buffer()));
    }

    @Test
    @DisplayName("nobody may act out of turn")
    void turnOrderIsEnforced() {
        var fixture = fight(1, 20); // goblin first
        fixture.start();

        assertThrows(IllegalArgumentException.class, () ->
                fixture.engine.combat().moveTo("fighter", 6, 2, new CombatSink.Buffer()));
    }

    @Test
    @DisplayName("ending a turn hands over, and wrapping round advances the round")
    void turnsAdvance() {
        var fixture = fight(20, 1);
        fixture.start();

        assertEquals("fighter", fixture.engine.combat().activeId());
        fixture.engine.combat().endTurn("fighter", new CombatSink.Buffer());
        assertEquals("goblin", fixture.engine.combat().activeId());
        assertEquals(1, fixture.engine.combat().view().round());

        fixture.engine.combat().endTurn("goblin", new CombatSink.Buffer());
        assertEquals("fighter", fixture.engine.combat().activeId());
        assertEquals(2, fixture.engine.combat().view().round());
    }

    // ---- The goblin ----

    @Test
    @DisplayName("the goblin closes the distance and swings in the same turn")
    void goblinClosesAndAttacks() {
        // initiative 1/20 (goblin first), then its attack 19 (+4 = 23 vs AC 16), damage 4.
        var fixture = fight(1, 20, 19, 4);
        fixture.start();
        fixture.place("fighter", 6, 1);
        fixture.place("goblin", 6, 6);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().runAutomaticTurns(sink, () -> {});

        assertTrue(fixture.get("goblin").isAdjacentTo(fixture.get("fighter")),
                "it should have moved into reach");
        assertEquals(6, fixture.get("fighter").hp(), "12 hp less 4+2 damage");
        assertEquals("fighter", fixture.engine.combat().activeId(), "and then ended its turn");
    }

    @Test
    @DisplayName("a goblin already in reach swings without shuffling")
    void adjacentGoblinDoesNotMove() {
        var fixture = fight(1, 20, 19, 4);
        fixture.start();
        fixture.place("fighter", 6, 5);
        fixture.place("goblin", 6, 6);

        fixture.engine.combat().runAutomaticTurns(new CombatSink.Buffer(), () -> {});

        assertEquals(new Square(6, 6),
                new Square(fixture.get("goblin").x(), fixture.get("goblin").y()));
    }
}
