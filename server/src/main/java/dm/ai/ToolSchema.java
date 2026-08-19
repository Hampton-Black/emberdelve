package dm.ai;

import dm.wire.Json;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dm.engine.GameEngine;
import dm.model.Difficulty;
import dm.model.Skill;

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
 * <p>Four tools, not the five in §7 as written: narration moves through the text channel so it
 * streams from the first token. See AGENTS.md → Deviations.
 */
public final class ToolSchema {


    public static final String ROLL_CHECK = "roll_check";
    public static final String REVEAL_PROP = "reveal_prop";
    public static final String SPAWN_ENTITY = "spawn_entity";
    public static final String START_COMBAT = "start_combat";

    private ToolSchema() {
    }

    /** The tools legal <em>right now</em>. Rebuilt each turn against current world state. */
    public static ArrayNode forTurn(GameEngine engine) {
        ArrayNode tools = Json.MAPPER.createArrayNode();

        var actorIds = engine.repo().entities().stream().map(e -> e.id()).toList();

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

        // Only offered while something is actually hidden — a tool with an empty enum is not
        // a valid schema, and one with nothing to reveal is an invitation to hallucinate.
        var hidden = engine.room().hiddenPropIds().stream()
                .filter(id -> !engine.repo().revealedPropIds().contains(id))
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
                    enumProp(properties, "kind", List.of("goblin"), "What to spawn.");
                    intProp(properties, "x", 0, engine.room().width() - 1);
                    intProp(properties, "y", 0, engine.room().height() - 1);
                },
                "kind", "x", "y"));

        tools.add(tool(START_COMBAT,
                "Switch to tactical combat mode. Call this when violence actually begins.",
                properties -> {
                }));

        return tools;
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

    private static List<String> lowerNames(Enum<?>[] values) {
        return Arrays.stream(values)
                .map(v -> v.name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
