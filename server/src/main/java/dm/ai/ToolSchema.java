package dm.ai;

import dm.wire.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.engine.GameEngine;
import dm.model.Consumable;
import dm.model.ClockId;
import dm.model.Difficulty;
import dm.model.Skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds the tool definitions sent with each turn.
 *
 * <p>Every enum is closed and derived from live state, not hardcoded prose (invariant #7). The
 * model cannot name a prop that does not exist, spawn a creature that is not statted, or invent
 * a difficulty band — the schema itself makes those unrepresentable, and the dispatcher
 * re-validates anyway.
 *
 * <p>Five tools. Narration still moves through the text channel so it streams from the first
 * token; {@code assert_fact} is the fifth, offered only on reconcile. See AGENTS.md → Deviations.
 */
public final class ToolSchema {


    public static final String ROLL_CHECK = "roll_check";
    public static final String REVEAL_PROP = "reveal_prop";
    public static final String SPAWN_ENTITY = "spawn_entity";
    public static final String START_COMBAT = "start_combat";
    public static final String ASSERT_FACT = "assert_fact";
    public static final String USE_EXIT = "use_exit";
    public static final String MOVE_ENTITY = "move_entity";
    public static final String USE_ITEM = "use_item";
    public static final String REST = "rest";
    public static final String TAKE_PROP = "take_prop";

    /** Everything {@code spawn_entity} can bring into the room. M0 has one creature. */
    public static final List<String> SPAWNABLE_KINDS = List.of("goblin");

    /**
     * What {@link #forReconcile} actually offers. See {@code DmService.runReconcilePhase}.
     *
     * <p>{@code USE_EXIT} is absent on purpose, and it is the one omission worth explaining.
     * Spec §6d: a tool belongs here when a false positive is cheap and the narrator holds the
     * information. Moving the party out of a room is neither — m2-evaluation §8 records the
     * mechanics model spawning a goblin and starting a fight off a spoken aside, and a room
     * change is louder than a fight.
     */
    private static final java.util.Set<String> RECONCILE_TOOLS =
            java.util.Set.of(REVEAL_PROP, SPAWN_ENTITY, START_COMBAT, ASSERT_FACT, MOVE_ENTITY);

    private ToolSchema() {
    }

    /**
     * Whether the reconcile pass is allowed to run this call.
     *
     * <p>Two things it screens out, both observed. A model asked for no tool calls very often
     * answers by calling one named {@code none} — a rejection the dispatcher is right to make and
     * that the caller must not read as "try again with better arguments", because there are no
     * better arguments. And {@code roll_check}, which is absent from the schema but which a model
     * that has seen it all session can still name; a die thrown here decides something the player
     * has already been told.
     */
    public static boolean allowedInReconcile(String toolName) {
        return RECONCILE_TOOLS.contains(toolName);
    }

    /** The tools legal <em>right now</em>. Rebuilt each turn against current world state. */
    public static ArrayNode forTurn(GameEngine engine) {
        return build(engine, true);
    }

    /**
     * The tools legal in the reconcile pass — everything except {@code roll_check}.
     *
     * <p>Reconcile runs after the narration has been written and, by the time its answer lands,
     * spoken. A die thrown at that point cannot inform anything: the outcome it decides has
     * already been described to the player, so the only thing a roll can do here is contradict
     * it. Reconcile makes the world match what was said; it does not adjudicate.
     */
    public static ArrayNode forReconcile(GameEngine engine) {
        return build(engine, false);
    }

    private static ArrayNode build(GameEngine engine, boolean withChecks) {
        ArrayNode tools = Json.MAPPER.createArrayNode();

        // The dead are not actors. A dead goblin offered to roll_check or move_entity is a
        // rejection at dispatch every single time, and for the mechanics model a rejection
        // counts towards having its tools taken away.
        var actorIds = engine.state().entitiesHere().stream()
                .filter(dm.model.Entity::isAlive)
                .map(dm.model.Entity::id)
                .toList();

        if (withChecks) {
            tools.add(tool(ROLL_CHECK,
                    "Resolve an action whose outcome is genuinely uncertain. Wait for the result "
                            + "before narrating what happened.",
                    properties -> {
                        enumProp(properties, "skill", lowerNames(Skill.values()),
                                "Which skill applies.");
                        enumProp(properties, "difficulty", lowerNames(Difficulty.values()),
                                "How hard this is, as a band.");
                        enumProp(properties, "actor_id", actorIds,
                                "Who is attempting it.");
                    },
                    "skill", "difficulty", "actor_id"));
        }

        // Only offered while something is actually hidden — a tool with an empty enum is not
        // a valid schema, and one with nothing to reveal is an invitation to hallucinate.
        var hidden = engine.room().hiddenPropIds().stream()
                .filter(id -> !engine.state().revealedHere().contains(id))
                .toList();
        if (!hidden.isEmpty()) {
            tools.add(tool(REVEAL_PROP,
                    "Make a hidden thing visible on the map, once the player has plausibly "
                            + "found it.",
                    properties -> enumProp(properties, "prop_id", hidden,
                            "Which hidden thing they found."),
                    "prop_id"));
        }

        tools.add(tool(SPAWN_ENTITY,
                "Bring a creature into the room at a specific square.",
                properties -> {
                    enumProp(properties, "kind", SPAWNABLE_KINDS, "What to spawn.");
                    intProp(properties, "x", 0, engine.room().width() - 1);
                    intProp(properties, "y", 0, engine.room().height() - 1);
                },
                "kind", "x", "y"));

        // Not offered once a fight is running. Both models kept calling it mid-combat and
        // collecting "combat has already started" — a wasted round trip each time, and for the
        // mechanics model a rejection that counts towards having its tools taken away. Invariant
        // #7's point is that the legal set is rebuilt from live state; this was the one tool
        // still being offered unconditionally.
        if (!engine.combat().isActive()) {
            tools.add(tool(START_COMBAT,
                    "Switch to tactical combat mode. Call this when violence actually begins.",
                    properties -> {
                    }));
        }

        // Not offered during a fight: crossExit refuses it, so offering it buys a round trip and
        // a rejection. Same reasoning as start_combat's guard directly above.
        var exits = engine.room().exits();
        if (withChecks && !engine.combat().isActive() && !exits.isEmpty()) {
            tools.add(tool(USE_EXIT,
                    "Take the party out of this room through one of its ways out. Only when the "
                            + "player has said they are leaving.",
                    properties -> enumProp(properties, "exit_id",
                            exits.stream().map(dm.model.Exit::id).toList(),
                            "Which way out they take."),
                    "exit_id"));
        }

        var usableItems = usableItems(engine);
        if (withChecks && !engine.combat().isActive() && !usableItems.isEmpty()) {
            tools.add(tool(USE_ITEM,
                    "Spend a potion or a torch from the party's supplies.",
                    properties -> {
                        enumProp(properties, "item", usableItems, "Which supply to spend.");
                        enumProp(properties, "actor_id", actorIds, "Who uses it.");
                    },
                    "item", "actor_id"));
        }

        if (withChecks && engine.canRest()) {
            tools.add(tool(REST,
                    "The party stops to rest and catch their breath. Ticks both clocks.",
                    properties -> enumProp(properties, "actor_id", actorIds, "Who rests."),
                    "actor_id"));
        }

        var takeable = takeablePropIds(engine);
        if (withChecks && !takeable.isEmpty()) {
            tools.add(tool(TAKE_PROP,
                    "Take something from this room that the party can carry. Only when the "
                            + "player has said they are taking it.",
                    properties -> enumProp(properties, "prop_id", takeable,
                            "Which thing they take."),
                    "prop_id"));
        }

        if (!actorIds.isEmpty()) {
            tools.add(tool(MOVE_ENTITY,
                    "Move someone to a square. Use it when the narration says they went "
                            + "somewhere, so the board matches what was said.",
                    properties -> {
                        enumProp(properties, "actor_id", actorIds, "Who moves.");
                        intProp(properties, "x", 0, engine.room().width() - 1);
                        intProp(properties, "y", 0, engine.room().height() - 1);
                    },
                    "actor_id", "x", "y"));
        }

        if (!withChecks) {
            ObjectNode assertFact = tool(ASSERT_FACT,
                    "Record something the narrator said that the board cannot hold — a smell, "
                            + "a sound, a scratch on a wall, a ring on a dead hand.",
                    properties -> {
                        stringProp(properties, "text",
                                "What is now true. Short. One fact.");
                        enumProp(properties, "anchor",
                                List.of("ambient", "at_square", "on"),
                                "Where it lives. ambient has no square; at_square needs x and y; "
                                        + "on needs target_id.");
                        nullableIntProp(properties, "x", 0, engine.room().width() - 1);
                        nullableIntProp(properties, "y", 0, engine.room().height() - 1);
                        nullableStringProp(properties, "target_id",
                                "The entity or prop this is attached to, when anchor is on.");
                    },
                    "text", "anchor", "x", "y", "target_id");
            ((ObjectNode) assertFact.get("function")).put("strict", true);
            tools.add(assertFact);
        }

        return tools;
    }

    private static List<String> takeablePropIds(GameEngine engine) {
        if (engine.combat().isActive() || engine.state().ending().isPresent()) {
            return List.of();
        }
        var taken = engine.state().takenHere();
        var revealed = engine.state().revealedHere();
        return engine.room().toProps().stream()
                .filter(p -> p.actions().contains("take"))
                .filter(p -> !p.hidden() || revealed.contains(p.id()))
                .filter(p -> !taken.contains(p.id()))
                .map(dm.model.Prop::id)
                .toList();
    }

    private static List<String> usableItems(GameEngine engine) {
        var items = new ArrayList<String>();
        if (engine.state().consumableCount(Consumable.POTION) > 0) {
            items.add("potion");
        }
        if (engine.state().consumableCount(Consumable.TORCH) > 0
                && engine.state().clock(ClockId.LIGHT).filled() > 0) {
            items.add("torch");
        }
        return items;
    }

    private static ObjectNode tool(
            String name, String description, java.util.function.Consumer<ObjectNode> build,
            String... required) {

        ObjectNode tool = Json.MAPPER.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);

        ObjectNode parameters = function.putObject("parameters");
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        build.accept(properties);

        ArrayNode requiredNames = parameters.putArray("required");
        Arrays.stream(required).forEach(requiredNames::add);
        parameters.put("additionalProperties", false);

        return tool;
    }

    private static void enumProp(
            ObjectNode properties, String name, List<String> values, String description) {

        ObjectNode prop = properties.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
        ArrayNode allowed = prop.putArray("enum");
        values.forEach(allowed::add);
    }

    private static void intProp(ObjectNode properties, String name, int min, int max) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "integer");
        prop.put("minimum", min);
        prop.put("maximum", max);
    }

    /** Strict schemas require every property; a missing optional is {@code null}, not absent. */
    private static void nullableIntProp(ObjectNode properties, String name, int min, int max) {
        ObjectNode prop = properties.putObject(name);
        var type = prop.putArray("type");
        type.add("integer");
        type.add("null");
        prop.put("minimum", min);
        prop.put("maximum", max);
    }

    private static void stringProp(ObjectNode properties, String name, String description) {
        ObjectNode prop = properties.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
    }

    private static void nullableStringProp(ObjectNode properties, String name, String description) {
        ObjectNode prop = properties.putObject(name);
        var type = prop.putArray("type");
        type.add("string");
        type.add("null");
        prop.put("description", description);
    }

    private static List<String> lowerNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(v -> v.name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
