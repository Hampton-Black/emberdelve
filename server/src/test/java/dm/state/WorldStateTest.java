package dm.state;

import dm.model.Anchor;
import dm.model.Combatant;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.model.Outcome;
import dm.model.RollRequest;
import dm.model.RollResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorldStateTest {

    private static final Instant T = Instant.parse("2026-08-23T12:00:00Z");

    private static Entity entity(String id, String kind, int hp, int x, int y, boolean player) {
        return new Entity(id, kind, id, 15, hp, hp, 4, "1d6", 2, 30, 2, x, y, player, Map.of());
    }

    private static RollResult roll(int face, int total, Outcome outcome) {
        return new RollResult(RollRequest.initiative("x", 0), List.of(face), total, outcome);
    }

    @Test
    @DisplayName("an empty log folds to an empty world")
    void emptyLog() {
        var state = WorldState.fold(List.of());
        assertEquals(Mode.EXPLORATION, state.mode());
        assertTrue(state.entities().isEmpty());
        assertTrue(state.combat().isEmpty());
    }

    @Test
    @DisplayName("spawns and moves fold to positions")
    void spawnAndMove() {
        var state = WorldState.fold(List.of(
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.EntityMoved(T, "goblin", 2, 2, 3, 4, 2)));

        var goblin = state.find("goblin").orElseThrow();
        assertEquals(3, goblin.x());
        assertEquals(4, goblin.y());
    }

    @Test
    @DisplayName("an attack folds to damage, and a kill folds to a corpse that stays put")
    void attackFolds() {
        var state = WorldState.fold(List.of(
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.AttackResolved(T, "fighter", "goblin",
                        roll(18, 22, Outcome.HIT),
                        Optional.of(roll(5, 8, Outcome.HIT)), 8, true, true)));

        var goblin = state.find("goblin").orElseThrow();
        assertEquals(0, goblin.hp(), "hp is clamped at zero, not negative");
        assertFalse(goblin.isAlive());
        assertTrue(state.entities().containsKey("goblin"), "the dead stay on the board");
        assertTrue(state.living().isEmpty());
    }

    @Test
    @DisplayName("a miss changes nothing but is still an event")
    void missFolds() {
        var state = WorldState.fold(List.of(
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.AttackResolved(T, "fighter", "goblin",
                        roll(3, 7, Outcome.MISS), Optional.empty(), 0, false, false)));

        assertEquals(7, state.find("goblin").orElseThrow().hp());
    }

    @Test
    @DisplayName("a fight folds to an order, a turn, and what that turn has left")
    void combatFolds() {
        var fighter = entity("fighter", "fighter", 12, 1, 1, true);
        var state = WorldState.fold(List.of(
                new Event.EntitySpawned(T, fighter),
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.CombatStarted(T, List.of(
                        new Combatant("fighter", "fighter", 18, true),
                        new Combatant("goblin", "goblin", 9, false)),
                        List.of(roll(16, 18, Outcome.SUCCESS), roll(7, 9, Outcome.SUCCESS)))));

        var combat = state.combat().orElseThrow();
        assertEquals("fighter", combat.activeId());
        assertEquals(1, combat.round());
        assertEquals(Mode.COMBAT, state.mode());
        assertEquals(fighter.speedSquares(), combat.movementRemaining());
        assertTrue(combat.actionAvailable());
    }

    @Test
    @DisplayName("moving and attacking spend the turn; advancing it gives the next one a full one")
    void turnResourcesFold() {
        var events = List.<Event>of(
                new Event.EntitySpawned(T, entity("fighter", "fighter", 12, 1, 1, true)),
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.CombatStarted(T, List.of(
                        new Combatant("fighter", "fighter", 18, true),
                        new Combatant("goblin", "goblin", 9, false)),
                        List.of(roll(16, 18, Outcome.SUCCESS), roll(7, 9, Outcome.SUCCESS))),
                new Event.EntityMoved(T, "fighter", 1, 1, 2, 1, 2),
                new Event.AttackResolved(T, "fighter", "goblin",
                        roll(3, 7, Outcome.MISS), Optional.empty(), 0, false, false));

        var spent = WorldState.fold(events).combat().orElseThrow();
        assertEquals(4, spent.movementRemaining(), "six squares of speed, two of them spent");
        assertFalse(spent.actionAvailable());

        var next = WorldState.fold(
                java.util.stream.Stream.concat(events.stream(),
                        java.util.stream.Stream.of(new Event.TurnAdvanced(T, "goblin", 1)))
                        .toList()).combat().orElseThrow();
        assertEquals("goblin", next.activeId());
        assertTrue(next.actionAvailable(), "a new turn arrives with its action unspent");
    }

    @Test
    @DisplayName("checks fold into the momentum counter, and a success clears it")
    void momentumFolds() {
        var fail = roll(4, 9, Outcome.FAILURE);
        var pass = roll(17, 22, Outcome.SUCCESS);

        assertEquals(2, WorldState.fold(List.of(
                new Event.CheckResolved(T, "fighter", Optional.empty(), 15, fail, Outcome.FAILURE),
                new Event.CheckResolved(T, "fighter", Optional.empty(), 15, fail, Outcome.FAILURE)))
                .consecutiveFailedChecks());

        assertEquals(0, WorldState.fold(List.of(
                new Event.CheckResolved(T, "fighter", Optional.empty(), 15, fail, Outcome.FAILURE),
                new Event.CheckResolved(T, "fighter", Optional.empty(), 15, pass, Outcome.SUCCESS)))
                .consecutiveFailedChecks());
    }

    @Test
    @DisplayName("asserted facts fold, and inputs fold to nothing")
    void factsAndInputs() {
        var state = WorldState.fold(List.of(
                new Event.FactAsserted(T, "f1", "crypt", "The air tastes of iron.", Anchor.AMBIENT),
                new Event.PlayerSaid(T, "fighter", "I look around."),
                new Event.ToolCallIssued(T, dm.model.Phase.MECHANICS, "roll_check", "{}", true, "ok")));

        assertEquals(1, state.facts().size(), "an input is not a state change");
        assertEquals("The air tastes of iron.", state.facts().getFirst().text());
    }

    @Test
    @DisplayName("the fold is the same however it is reached")
    void foldEqualsIncrementalApply() {
        var events = List.<Event>of(
                new Event.EntitySpawned(T, entity("goblin", "goblin", 7, 2, 2, false)),
                new Event.EntityMoved(T, "goblin", 2, 2, 3, 3, 1),
                new Event.PropRevealed(T, "crypt", "loose-flagstone"));

        var byFold = WorldState.fold(events);
        var byApply = events.stream().reduce(WorldState.EMPTY, WorldState::apply, (a, b) -> b);

        assertEquals(byFold, byApply);
    }
}
