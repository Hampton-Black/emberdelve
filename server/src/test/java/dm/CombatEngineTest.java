package dm;

import dm.content.ContentLoader;
import dm.engine.CombatEngine;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.RollPurpose;
import dm.model.Square;
import dm.state.EventLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Attack resolution and the legality rules the client is not allowed to compute for itself.
 *
 * <p>Every roll here comes from a scripted die, so a failure means the engine changed and not
 * that the test got unlucky — the reason {@code DiceRoller} is injected rather than static.
 */
class CombatEngineTest {

    /** Fighter: AC 16, 20 hp, +5 to hit, 1d8+3, DEX +1. Goblin: AC 15, 7 hp, +4, 1d6+2, DEX +2. */
    private static Fixture fight(Integer... faces) {
        var log = new EventLog();
        var engine = new GameEngine(new ContentLoader(), log, new ScriptedDiceRoller(faces));
        engine.start();
        engine.spawnGoblin(6, 6);
        return new Fixture(engine, log);
    }

    private record Fixture(GameEngine engine, EventLog log) {
        CombatSink.Buffer start() {
            var sink = new CombatSink.Buffer();
            engine.combat().start(sink);
            return sink;
        }

        void place(String id, int x, int y) {
            var entity = engine.state().find(id).orElseThrow();
            log.append(new Event.EntityMoved(Instant.now(), id, entity.x(), entity.y(), x, y, 0));
        }

        Entity get(String id) {
            return engine.state().find(id).orElseThrow();
        }

        void put(Entity entity) {
            log.append(new Event.EntitySpawned(Instant.now(), entity));
        }
    }

    private dm.state.EventLog log;
    private CombatSink.Buffer sink;

    private CombatEngine engineWith(List<Integer> faces) {
        log = new dm.state.EventLog();
        sink = new CombatSink.Buffer();
        var content = new ContentLoader();
        var game = new GameEngine(content, log, new ScriptedDiceRoller(faces));
        game.start();
        game.spawnGoblin(game.defaultGoblinSpawn().x(), game.defaultGoblinSpawn().y());
        return game.combat();
    }

    @Test
    @DisplayName("an attack is recorded as one fact carrying both sides and both rolls")
    void attackIsOneEvent() {
        // A hit: 18 on the die beats AC, then 4 on the damage die.
        var engine = engineWith(List.of(20, 1, 18, 4));
        engine.start(sink);
        // Start positions are not adjacent; close so the attack is legal. Movement is not a roll.
        engine.moveTo(engine.view().activeId(), 6, 5, sink);
        var attacker = engine.view().activeId();
        var target = engine.view().legalTargets().getFirst();

        engine.attack(attacker, target, sink);

        var resolved = log.events().stream()
                .filter(dm.model.Event.AttackResolved.class::isInstance)
                .map(dm.model.Event.AttackResolved.class::cast)
                .toList();

        assertEquals(1, resolved.size(), "one attack, one fact — not a damage and a death");
        var attack = resolved.getFirst();
        assertEquals(attacker, attack.actorId());
        assertEquals(target, attack.targetId());
        assertTrue(attack.hit());
        assertTrue(attack.damage().isPresent());
        assertTrue(attack.damageDealt() > 0);
    }

    @Test
    @DisplayName("a fight's order and rounds are folded from the log, not held beside it")
    void combatStateComesFromTheFold() {
        var engine = engineWith(List.of(20, 1));
        engine.start(sink);

        var record = log.state().combat().orElseThrow();
        assertEquals(engine.view().activeId(), record.activeId());
        assertEquals(engine.view().round(), record.round());
        assertEquals(engine.view().order().size(), record.order().size());
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
        assertEquals(Mode.COMBAT, fixture.engine.state().mode());
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
        fixture.put(fixture.get("goblin").withHp(12));

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
        assertEquals(Mode.EXPLORATION, fixture.engine.state().mode());
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
        assertEquals(14, fixture.get("fighter").hp(), "20 hp less 4+2 damage");
        assertEquals("fighter", fixture.engine.combat().activeId(), "and then ended its turn");
    }

    // ---- What the narrator is handed ----

    @Test
    @DisplayName("a hit states who, whom and how much — and how hurt they now look")
    void hitReportsFacts() {
        var fixture = fight(20, 1, 14, 4);
        fixture.start();
        fixture.place("fighter", 6, 5);
        fixture.put(fixture.get("goblin").withHp(7));

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        // The display name above is what this beat is for, and the old assertion did not hold
        // the engine to it: a killing blow used to report only the death, with no attacker in
        // it at all. m2-evaluation.md §8.
        assertEquals(
                List.of("You hit Vessk for 7 damage. Vessk is killed by the blow."),
                sink.collectedBeats());
    }

    @Test
    @DisplayName("a wound is described, never counted — the narrator must not read hit points out")
    void woundIsDescribedNotCounted() {
        // The goblin swings, because only the fighter has enough hit points to survive a hit and
        // still be a fraction. 12 less 4+2 is half, which is "bloodied" rather than "6/12".
        var fixture = fight(1, 20, 14, 4);
        fixture.start();
        fixture.place("goblin", 6, 5);
        fixture.place("fighter", 6, 4);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("goblin", "fighter", sink);

        var beat = sink.collectedBeats().getLast();
        assertEquals("Vessk hits you for 6 damage. You are now bloodied.", beat);
        assertFalse(beat.contains("/") || beat.toLowerCase().contains("hp"),
                "a hit-point count here invites the model to say it");
    }

    @Test
    @DisplayName("a crit says so, so the narrator can spend a sentence on it")
    void critIsCalledOut() {
        var fixture = fight(20, 1, 20, 3, 3);
        fixture.start();
        fixture.place("fighter", 6, 5);
        var goblin = fixture.get("goblin");
        fixture.put(new Entity(goblin.id(), goblin.kind(), goblin.name(), goblin.ac(),
                20, 20, goblin.toHit(), goblin.damageDice(), goblin.damageModifier(),
                goblin.speedFeet(), goblin.initiativeModifier(), "crypt", goblin.x(), goblin.y(),
                goblin.isPlayerControlled(), goblin.skillModifiers()));

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);

        assertTrue(sink.collectedBeats().getFirst().contains("critical"),
                sink.collectedBeats().toString());
    }

    @Test
    @DisplayName("a miss is a fact too, and a fumble is a different one")
    void missesAreReported() {
        var fixture = fight(20, 1, 2, 1);
        fixture.start();
        fixture.place("fighter", 6, 5);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", sink);
        assertEquals(List.of("You swing at Vessk and miss."), sink.collectedBeats());

        var fumble = new CombatSink.Buffer();
        fixture.engine.combat().endTurn("fighter", fumble);
        fixture.engine.combat().endTurn("goblin", fumble);
        var second = new CombatSink.Buffer();
        fixture.engine.combat().attack("fighter", "goblin", second);
        assertTrue(second.collectedBeats().getFirst().contains("fumble"),
                second.collectedBeats().toString());
    }

    @Test
    @DisplayName("the goblin's whole turn arrives as one set of facts, movement included")
    void enemyTurnReportsMovementAndAttack() {
        var fixture = fight(1, 20, 19, 4);
        fixture.start();
        fixture.place("fighter", 6, 1);
        fixture.place("goblin", 6, 6);

        var sink = new CombatSink.Buffer();
        fixture.engine.combat().runAutomaticTurns(sink, () -> {});

        var beats = sink.collectedBeats();
        assertEquals(2, beats.size(), beats.toString());
        assertEquals("Vessk closes the distance to you.", beats.getFirst());
        assertFalse(beats.getFirst().contains("Roderick"),
                "the player is never named in the third person in a beat");
        assertTrue(beats.get(1).contains("hits you"), beats.toString());
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

    // ---- The dead stay dead ----

    @Test
    @DisplayName("spawning again does not raise the goblin that already died")
    void spawningDoesNotResurrect() {
        var fixture = fight(20, 20, 20, 20, 20, 20);

        // Kill him outright rather than rolling for it: this is about what a spawn does to a
        // corpse, not about how the corpse got there.
        fixture.put(fixture.get("goblin").damaged(99));
        assertFalse(fixture.get("goblin").isAlive(), "setup: the goblin should be dead");

        var refused = assertThrows(IllegalArgumentException.class,
                () -> fixture.engine().spawnGoblin(6, 6));
        assertTrue(refused.getMessage().contains("dead"), refused.getMessage());

        assertFalse(fixture.get("goblin").isAlive(),
                "a spawn brought the dead goblin back to full health");
    }

    @Test
    @DisplayName("a dead hostile never rolls initiative, however many times combat is started")
    void deadHostileNeverRollsInitiative() {
        var fixture = fight(20, 20, 20, 20, 20, 20);

        fixture.put(fixture.get("goblin").damaged(99));
        assertThrows(IllegalArgumentException.class, () -> fixture.engine().spawnGoblin(6, 6));

        var sink = fixture.start();

        assertTrue(
                sink.collectedRolls().stream().noneMatch(
                        r -> r.request().purpose() == RollPurpose.INITIATIVE
                                && "goblin".equals(r.request().actorId())),
                "a dead goblin rolled for initiative");
    }
}
