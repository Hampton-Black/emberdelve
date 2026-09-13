package dm.engine;

import dm.ai.DmClient;
import dm.ai.ToolDispatcher;
import dm.ai.ToolSchema;
import dm.content.ContentLoader;
import dm.model.Diff;
import dm.model.Entity;
import dm.model.Event;
import dm.model.Mode;
import dm.replay.ReplayRunner;
import dm.state.EventLog;
import dm.state.SessionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * emberdelve-4h9.9: more than one hostile on the board, a retuned goblin, and a brute.
 * Spec §5a / §8f. ADR-0013.
 */
class HostileRosterTest {

    private static final ContentLoader CONTENT = new ContentLoader();
    private static final Instant T = Instant.parse("2026-09-13T08:00:00Z");

    private static GameEngine started(EventLog log, Integer... faces) {
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(faces));
        engine.start();
        return engine;
    }

    private static GameEngine started(Integer... faces) {
        return started(new EventLog(), faces);
    }

    private static List<Entity> livingHostiles(GameEngine engine) {
        return engine.state().entitiesHere().stream()
                .filter(e -> e.isAlive() && !e.isPlayerControlled())
                .toList();
    }

    @Test
    @DisplayName("goblin content is the mob: AC 12, HP 6, +3, 1d4+1")
    void goblinContentIsTheMob() {
        var goblin = CONTENT.entity("goblin");
        assertEquals(12, goblin.ac());
        assertEquals(6, goblin.maxHp());
        assertEquals(3, goblin.toHit());
        assertEquals("1d4", goblin.damageDice());
        assertEquals(1, goblin.damageModifier());
        assertEquals(30, goblin.speedFeet());
        assertFalse(goblin.isPlayerControlled());
    }

    @Test
    @DisplayName("brute content is AC 13, HP 16, +4, 1d8+2, speed 30, not player-controlled")
    void bruteContentBlock() {
        var brute = CONTENT.entity("brute");
        assertEquals("brute", brute.id());
        assertEquals("brute", brute.kind());
        assertEquals(13, brute.ac());
        assertEquals(16, brute.maxHp());
        assertEquals(4, brute.toHit());
        assertEquals("1d8", brute.damageDice());
        assertEquals(2, brute.damageModifier());
        assertEquals(30, brute.speedFeet());
        assertFalse(brute.isPlayerControlled());
    }

    @Test
    @DisplayName("SCHEMA_VERSION stays 3")
    void schemaVersionStaysThree() {
        assertEquals(3, Event.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("two goblins take distinct ids and can stand in the same room")
    void twoGoblinsTakeDistinctIds() {
        var engine = started(10);
        engine.spawnGoblin(6, 6);
        engine.spawnGoblin(5, 6);

        var goblins = livingHostiles(engine);
        assertEquals(2, goblins.size());
        assertEquals(Set.of("goblin", "goblin-2"),
                goblins.stream().map(Entity::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(goblins.stream().allMatch(e -> "goblin".equals(e.kind())));
        assertTrue(engine.state().find("goblin").orElseThrow().isAlive());
        assertTrue(engine.state().find("goblin-2").orElseThrow().isAlive());
    }

    @Test
    @DisplayName("a second spawn does not raise a goblin that already died")
    void secondSpawnDoesNotResurrect() {
        var engine = started(10);
        var log = engine.log();
        engine.spawnGoblin(6, 6);
        var corpse = engine.state().find("goblin").orElseThrow().damaged(99);
        log.append(new Event.EntitySpawned(Instant.now(), corpse));
        assertFalse(engine.state().find("goblin").orElseThrow().isAlive());

        engine.spawnGoblin(5, 6);

        assertFalse(engine.state().find("goblin").orElseThrow().isAlive(),
                "the original corpse stays dead");
        assertTrue(engine.state().find("goblin-2").orElseThrow().isAlive());
        assertEquals(6, engine.state().find("goblin-2").orElseThrow().hp());
    }

    @Test
    @DisplayName("two goblins and a brute are alive in one room; initiative includes all three; each gets a turn")
    void packRollsInitiativeAndEachHostileActs() {
        var log = new EventLog();
        // livingEntities id order: brute, fighter, goblin, goblin-2.
        // High faces for the hostiles, low for the fighter, so the three go first.
        var engine = started(log, 20, 1, 20, 20, 10, 10, 10, 10, 10, 10, 10, 10);
        engine.spawnGoblin(6, 6);
        engine.spawnGoblin(5, 6);
        engine.spawnHostile("brute", 7, 6);

        var hostiles = livingHostiles(engine);
        assertEquals(3, hostiles.size());
        assertEquals(Set.of("goblin", "brute"),
                hostiles.stream().map(Entity::kind).collect(java.util.stream.Collectors.toSet()));

        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> {});

        var order = engine.combat().view().order().stream()
                .map(dm.model.Combatant::entityId)
                .toList();
        assertEquals(4, order.size(), order.toString());
        assertTrue(order.contains("fighter"));
        assertTrue(order.contains("goblin"));
        assertTrue(order.contains("goblin-2"));
        assertTrue(order.contains("brute"));

        var advanced = log.events().stream()
                .filter(Event.TurnAdvanced.class::isInstance)
                .map(Event.TurnAdvanced.class::cast)
                .map(Event.TurnAdvanced::activeId)
                .toList();
        assertEquals(3, advanced.size(), "each of the three hostiles ends, handing over: " + advanced);
        assertEquals("fighter", engine.combat().activeId());
        assertEquals(Mode.COMBAT, engine.state().mode());
    }

    @Test
    @DisplayName("debug start-combat packs two goblins and a brute when the room is empty of hostiles")
    void debugStartCombatPacksTwoKinds() {
        var engine = started(20, 1, 20, 20, 10, 10, 10, 10, 10, 10, 10, 10);
        assertTrue(livingHostiles(engine).isEmpty());

        var spawned = engine.ensureDebugHostiles();
        assertFalse(spawned.isEmpty());

        var hostiles = livingHostiles(engine);
        assertEquals(3, hostiles.size(), hostiles.toString());
        var kinds = hostiles.stream().map(Entity::kind).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("goblin", "brute"), kinds);

        var sink = new CombatSink.Buffer();
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> {});

        assertTrue(engine.combat().isActive());
        assertEquals(4, engine.combat().view().order().size());
        assertEquals("fighter", engine.combat().activeId());
    }

    @Test
    @DisplayName("ensureDebugHostiles leaves an already-spawned hostile alone")
    void debugPackDoesNotStackOnAnExistingHostile() {
        var engine = started(10);
        engine.spawnGoblin(6, 6);

        assertTrue(engine.ensureDebugHostiles().isEmpty());
        assertEquals(1, livingHostiles(engine).size());
        assertEquals("goblin", livingHostiles(engine).getFirst().id());
    }

    @Test
    @DisplayName("revealProp of the sarcophagus spawns the crypt goblin with no tool call")
    void revealingTheSarcophagusSpawnsTheGoblin() {
        var engine = started(10);
        assertTrue(engine.state().find("goblin").isEmpty());

        var diffs = engine.revealProp("sarcophagus");

        var goblin = engine.state().find("goblin").orElseThrow();
        assertEquals("goblin", goblin.kind());
        assertTrue(goblin.isAlive());
        var at = engine.defaultGoblinSpawn();
        assertEquals(at.x(), goblin.x());
        assertEquals(at.y(), goblin.y());
        assertTrue(diffs.stream().anyMatch(d -> d instanceof Diff.EntityAdded a
                && "goblin".equals(a.entity().id())));
    }

    @Test
    @DisplayName("the sarcophagus does not spawn a second goblin when that kind is already here")
    void sarcophagusDoesNotDuplicateAGoblinAlreadyHere() {
        var engine = started(10);
        engine.spawnGoblin(6, 6);

        engine.revealProp("sarcophagus");

        assertEquals(1, livingHostiles(engine).stream().filter(e -> "goblin".equals(e.kind())).count());
    }

    @Test
    @DisplayName("spawn_entity schema enum is still goblin-only")
    void spawnEntitySchemaStaysGoblinOnly() {
        var engine = started(10);
        var schema = ToolSchema.forTurn(engine).toString();
        assertTrue(schema.contains(ToolSchema.SPAWN_ENTITY));
        assertEquals(List.of("goblin"), ToolSchema.SPAWNABLE_KINDS);

        var spawn = ToolSchema.forTurn(engine).findParents("name").stream()
                .filter(n -> n.path("name").asText().equals(ToolSchema.SPAWN_ENTITY))
                .findFirst().orElseThrow();
        var kinds = spawn.path("parameters").path("properties").path("kind").path("enum");
        assertEquals(1, kinds.size(), kinds.toString());
        assertEquals("goblin", kinds.get(0).asText());
        assertFalse(schema.contains("\"brute\""), "the model must not be offered the brute");
    }

    @Test
    @DisplayName("the dispatcher rejects spawn_entity of a brute")
    void dispatcherRejectsSpawningABrute() {
        var engine = started(10);
        var result = new ToolDispatcher(engine).dispatch(new DmClient.ToolCall(
                "1", ToolSchema.SPAWN_ENTITY, "{\"kind\":\"brute\",\"x\":5,\"y\":5}"));

        assertFalse(result.ok(), result.message());
        assertTrue(result.message().toLowerCase().contains("goblin"), result.message());
        assertTrue(livingHostiles(engine).isEmpty());
    }

    @Test
    @DisplayName("spawn_entity may mint a second goblin")
    void dispatcherAllowsASecondGoblin() {
        var engine = started(10);
        var dispatcher = new ToolDispatcher(engine);
        var first = dispatcher.dispatch(new DmClient.ToolCall(
                "1", ToolSchema.SPAWN_ENTITY, "{\"kind\":\"goblin\",\"x\":6,\"y\":6}"));
        var second = dispatcher.dispatch(new DmClient.ToolCall(
                "2", ToolSchema.SPAWN_ENTITY, "{\"kind\":\"goblin\",\"x\":5,\"y\":6}"));

        assertTrue(first.ok(), first.message());
        assertTrue(second.ok(), second.message());
        assertEquals(2, livingHostiles(engine).size());
    }

    @Test
    @DisplayName("replay of EntitySpawned for a brute does not mint a goblin")
    void replayOfABruteKeepsTheBrute(@TempDir Path dir) {
        var writer = SessionWriter.open(dir);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));
        var engine = new GameEngine(CONTENT, log, new ScriptedDiceRoller(10));
        engine.start();
        var brute = new Entity("brute", "brute", "Brakk",
                13, 16, 16, 4, "1d8", 2, 30, 1, "crypt", 5, 5, false, Map.of());
        engine.spawnRecorded(brute);
        writer.close();

        assertEquals("brute", engine.state().find("brute").orElseThrow().kind());

        var result = ReplayRunner.replay(writer.path());
        assertTrue(result.matched(), "divergence at: " + result.firstDivergence());

        var replayed = EventLog.load(writer.path()).state();
        var entity = replayed.find("brute").orElseThrow();
        assertEquals("brute", entity.kind());
        assertEquals(13, entity.ac());
        assertEquals(16, entity.maxHp());
        assertTrue(replayed.find("goblin").isEmpty(), "replay must not mint a goblin");
    }
}
