package dm;

import dm.engine.RandomDiceRoller;
import dm.engine.ScriptedDiceRoller;
import dm.model.Advantage;
import dm.model.Difficulty;
import dm.model.Outcome;
import dm.model.RollPurpose;
import dm.model.RollRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DiceRollerTest {

    @Test
    @DisplayName("faces is a list of individual dice, never collapsed to a total")
    void facesArePerDie() {
        var roller = new ScriptedDiceRoller(3, 5, 2);
        var result = roller.roll(RollRequest.damage("fighter", "3d8", 0));

        assertEquals(java.util.List.of(3, 5, 2), result.faces());
        assertEquals(10, result.total());
    }

    @Test
    @DisplayName("modifier is added to the total but never to the faces")
    void modifierDoesNotPolluteFaces() {
        var roller = new ScriptedDiceRoller(4);
        var result = roller.roll(RollRequest.damage("fighter", "1d8", 3));

        assertEquals(java.util.List.of(4), result.faces());
        assertEquals(7, result.total());
    }

    @Test
    @DisplayName("scripted roller repeats its script rather than running dry")
    void scriptWrapsAround() {
        var roller = new ScriptedDiceRoller(6, 1);

        assertEquals(java.util.List.of(6, 1), roller.roll(RollRequest.damage("a", "2d6", 0)).faces());
        assertEquals(java.util.List.of(6, 1), roller.roll(RollRequest.damage("a", "2d6", 0)).faces());
    }

    @Test
    @DisplayName("a face larger than the die is clamped to the die")
    void scriptedFacesAreClamped() {
        var roller = new ScriptedDiceRoller(20);
        var result = roller.roll(RollRequest.damage("a", "1d6", 0));

        assertEquals(6, result.faces().getFirst());
    }

    @Test
    @DisplayName("advantage keeps the higher die and records the discard")
    void advantageKeepsHigher() {
        var roller = new ScriptedDiceRoller(7, 15);
        var request = new RollRequest("1d20", 0, Advantage.ADVANTAGE, RollPurpose.SKILL_CHECK,
                "fighter", Optional.empty(), Optional.of(10));
        var result = roller.roll(request);

        assertEquals(15, result.natural(), "kept die must be first");
        assertEquals(2, result.faces().size(), "discarded die is retained for the UI");
        assertTrue(result.faces().contains(7));
        assertEquals(15, result.total(), "the discarded die must not count toward the total");
    }

    @Test
    @DisplayName("disadvantage keeps the lower die")
    void disadvantageKeepsLower() {
        var roller = new ScriptedDiceRoller(7, 15);
        var request = new RollRequest("1d20", 0, Advantage.DISADVANTAGE, RollPurpose.SKILL_CHECK,
                "fighter", Optional.empty(), Optional.of(10));
        var result = roller.roll(request);

        assertEquals(7, result.natural());
        assertEquals(7, result.total());
    }

    @Test
    @DisplayName("skill checks pass and fail against the banded DC")
    void skillCheckAgainstDc() {
        var request = RollRequest.skillCheck("fighter", 3, Difficulty.MEDIUM); // DC 15

        assertEquals(Outcome.SUCCESS, new ScriptedDiceRoller(12).roll(request).outcome(), "12+3=15");
        assertEquals(Outcome.FAILURE, new ScriptedDiceRoller(11).roll(request).outcome(), "11+3=14");
    }

    @Test
    @DisplayName("difficulty bands map to the five permitted DCs")
    void difficultyBands() {
        assertEquals(5, Difficulty.TRIVIAL.dc());
        assertEquals(10, Difficulty.EASY.dc());
        assertEquals(15, Difficulty.MEDIUM.dc());
        assertEquals(20, Difficulty.HARD.dc());
        assertEquals(25, Difficulty.VERY_HARD.dc());
    }

    @Test
    @DisplayName("a seeded roller is reproducible")
    void seededIsReproducible() {
        var a = RandomDiceRoller.seeded(42);
        var b = RandomDiceRoller.seeded(42);
        var request = RollRequest.damage("fighter", "5d6", 0);

        assertEquals(a.roll(request).faces(), b.roll(request).faces());
    }

    @Test
    @DisplayName("random dice stay within their faces over many rolls")
    void randomDiceAreInRange() {
        var roller = new RandomDiceRoller();
        var request = RollRequest.damage("fighter", "1d20", 0);

        for (int i = 0; i < 500; i++) {
            int face = roller.roll(request).faces().getFirst();
            assertTrue(face >= 1 && face <= 20, "d20 rolled " + face);
        }
    }

    @Test
    @DisplayName("a malformed dice expression is rejected")
    void badExpressionRejected() {
        var roller = new ScriptedDiceRoller(1);
        var request = RollRequest.damage("fighter", "banana", 0);

        assertThrows(IllegalArgumentException.class, () -> roller.roll(request));
    }

    @Test
    @DisplayName("a check with no DC is a programming error, not a silent pass")
    void skillCheckWithoutDcThrows() {
        var roller = new ScriptedDiceRoller(10);
        var request = new RollRequest("1d20", 0, Advantage.NORMAL, RollPurpose.SKILL_CHECK,
                "fighter", Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> roller.roll(request));
    }
}
