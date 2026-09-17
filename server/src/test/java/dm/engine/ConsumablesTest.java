package dm.engine;

import dm.ai.ToolSchema;
import dm.content.ContentLoader;
import dm.model.ClockId;
import dm.model.Consumable;
import dm.model.Diff;
import dm.model.Directive;
import dm.model.Event;
import dm.model.Outcome;
import dm.model.Difficulty;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Skill;
import dm.state.EventLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.2: consumables as a counter map and {@code use_item}.
 */
class ConsumablesTest {

    private EventLog log;
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        log = new EventLog();
        engine = new GameEngine(new ContentLoader(), log, new RandomDiceRoller());
        engine.start();
    }

    private <T extends Event> List<T> eventsOf(Class<T> type) {
        return log.events().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    @DisplayName("starting grants two potions, two torches, and no rope")
    void grantAtStart() {
        var granted = eventsOf(Event.ConsumablesGranted.class).getLast();
        assertEquals(Map.of(Consumable.POTION, 2, Consumable.TORCH, 2, Consumable.ROPE, 0),
                granted.counts());
        assertEquals(2, engine.state().consumableCount(Consumable.POTION));
        assertEquals(2, engine.state().consumableCount(Consumable.TORCH));
        assertEquals(0, engine.state().consumableCount(Consumable.ROPE));
    }

    @Test
    @DisplayName("a potion restores eight hit points and spends the count")
    void potionHealsAndSpends() {
        damageFighterTo(10);

        var diffs = engine.useItem("fighter", Consumable.POTION);

        assertEquals(18, engine.state().find("fighter").orElseThrow().hp());
        assertEquals(1, engine.state().consumableCount(Consumable.POTION));
        var used = eventsOf(Event.ItemUsed.class).getLast();
        assertEquals(Consumable.POTION, used.item());
        assertEquals(1, used.remaining());
        assertTrue(diffs.stream().anyMatch(Diff.StatChanged.class::isInstance));
        assertTrue(diffs.stream().anyMatch(Diff.ConsumablesChanged.class::isInstance));
    }

    @Test
    @DisplayName("a third potion is refused when none remain")
    void potionRefusedAtZero() {
        engine.useItem("fighter", Consumable.POTION);
        engine.useItem("fighter", Consumable.POTION);

        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.POTION));
        assertEquals(0, engine.state().consumableCount(Consumable.POTION));
    }

    @Test
    @DisplayName("a potion at full hit points still spends and stays clamped")
    void potionAtFullHpStillSpends() {
        assertEquals(20, engine.state().find("fighter").orElseThrow().hp());

        engine.useItem("fighter", Consumable.POTION);

        assertEquals(20, engine.state().find("fighter").orElseThrow().hp());
        assertEquals(1, engine.state().consumableCount(Consumable.POTION));
    }

    @Test
    @DisplayName("a torch resets LIGHT to empty and spends the count")
    void torchResetsLight() {
        engine.tickClock(ClockId.LIGHT);
        engine.tickClock(ClockId.LIGHT);
        engine.tickClock(ClockId.LIGHT);
        assertEquals(3, engine.state().clock(ClockId.LIGHT).filled());

        engine.useItem("fighter", Consumable.TORCH);

        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());
        assertEquals(1, engine.state().consumableCount(Consumable.TORCH));
        assertEquals(Consumable.TORCH, eventsOf(Event.ItemUsed.class).getLast().item());
    }

    @Test
    @DisplayName("a torch at LIGHT empty is refused")
    void torchRefusedAtLightZero() {
        assertEquals(0, engine.state().clock(ClockId.LIGHT).filled());

        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.TORCH));
        assertEquals(2, engine.state().consumableCount(Consumable.TORCH));
    }

    @Test
    @DisplayName("rope, zero counts, and combat are refused")
    void illegalUsesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.ROPE));

        engine.useItem("fighter", Consumable.POTION);
        engine.useItem("fighter", Consumable.POTION);
        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.POTION));

        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());
        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.POTION));
        assertThrows(IllegalArgumentException.class,
                () -> engine.useItem("fighter", Consumable.TORCH));
    }

    @Test
    @DisplayName("a torch and a potion each latch a party-scoped clause")
    void itemDirectives() {
        engine.tickClock(ClockId.LIGHT);

        engine.useItem("fighter", Consumable.TORCH);

        var waiting = engine.directives().snapshot();
        assertEquals(1, waiting.size());
        assertEquals(Directive.About.PARTY, waiting.getFirst().about());
        assertEquals(ClockTables.TORCH_RELIT, waiting.getFirst().clause());

        // Spec §8d left the potion silent because it moves no clock; play reversed it
        // (emberdelve-kac).
        engine.directives().clear();
        engine.useItem("fighter", Consumable.POTION);
        waiting = engine.directives().snapshot();
        assertEquals(1, waiting.size());
        assertEquals(Directive.About.PARTY, waiting.getFirst().about());
        assertEquals(ClockTables.POTION_DRUNK, waiting.getFirst().clause());
    }

    @Test
    @DisplayName("use_item is mechanics-only and offers only usable items")
    void toolSchema() {
        engine.tickClock(ClockId.LIGHT);
        var names = ToolSchema.forTurn(engine).findValuesAsText("name");

        assertTrue(names.contains(ToolSchema.USE_ITEM));
        assertFalse(ToolSchema.forReconcile(engine).findValuesAsText("name")
                .contains(ToolSchema.USE_ITEM));
        assertFalse(ToolSchema.allowedInReconcile(ToolSchema.USE_ITEM));

        var useItem = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.USE_ITEM))
                .findFirst().orElseThrow();
        var items = useItem.path("parameters").path("properties").path("item").path("enum");
        assertEquals(2, items.size());
        assertTrue(items.toString().contains("potion"));
        assertTrue(items.toString().contains("torch"));
        assertFalse(items.toString().contains("rope"));

        engine.tickClock(ClockId.LIGHT);
        var afterTick = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.USE_ITEM))
                .findFirst().orElseThrow()
                .path("parameters").path("properties").path("item").path("enum");
        assertEquals(2, afterTick.size(), "torch stays offered while LIGHT is not empty");

        engine.useItem("fighter", Consumable.POTION);
        engine.useItem("fighter", Consumable.POTION);
        var noPotions = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.USE_ITEM))
                .findFirst().orElseThrow()
                .path("parameters").path("properties").path("item").path("enum");
        assertEquals(1, noPotions.size());
        assertEquals("torch", noPotions.get(0).asText());
    }

    @Test
    @DisplayName("the dispatcher rejects illegal use_item calls with a message")
    void dispatcherRejects() {
        var dispatcher = new dm.ai.ToolDispatcher(engine);
        engine.spawnGoblin(6, 6);
        engine.combat().start(new CombatSink.Buffer());

        var rejected = dispatcher.dispatch(new dm.ai.DmClient.ToolCall(
                "test", ToolSchema.USE_ITEM, "{\"actor_id\":\"fighter\",\"item\":\"potion\"}"));
        assertFalse(rejected.ok());
        assertTrue(rejected.message().contains("REJECTED"));
    }

    @Test
    @DisplayName("a natural 1 that fills LIGHT pushes canSpendTorch to the client")
    void naturalOneUpdatesCanSpendTorch() {
        assertFalse(engine.scene().canSpendTorch(), "LIGHT empty at start");

        var nat1 = new GameEngine(new ContentLoader(), new EventLog(), new ScriptedDiceRoller(1));
        nat1.start();
        assertFalse(nat1.scene().canSpendTorch());

        nat1.rollCheck("fighter", Skill.ATHLETICS, Difficulty.MEDIUM);

        assertTrue(nat1.scene().canSpendTorch());
        var diffs = nat1.takePendingDiffs();
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.ConsumablesChanged cc
                && cc.canSpendTorch()));
    }

    @Test
    @DisplayName("scene carries consumable counts and canSpendTorch without segment counts")
    void sceneState() {
        engine.tickClock(ClockId.LIGHT);
        var scene = engine.scene();

        assertEquals(2, scene.potions());
        assertEquals(2, scene.torches());
        assertEquals(0, scene.rope());
        assertTrue(scene.canSpendTorch());

        engine.useItem("fighter", Consumable.TORCH);
        var after = engine.scene();
        assertEquals(1, after.torches());
        assertFalse(after.canSpendTorch(), "LIGHT is empty after a torch");
    }

    private void damageFighterTo(int hp) {
        int damage = engine.state().find("fighter").orElseThrow().hp() - hp;
        var attackRoll = new RollResult(
                RollRequest.attack("goblin", "fighter", 4, 16),
                List.of(18), 22, Outcome.HIT);
        var damageRoll = new RollResult(
                RollRequest.damage("goblin", "1d6", 2), List.of(damage), damage + 2, Outcome.HIT);
        log.append(new Event.AttackResolved(java.time.Instant.now(), "goblin", "fighter",
                attackRoll, java.util.Optional.of(damageRoll), damage, true, false));
    }
}
