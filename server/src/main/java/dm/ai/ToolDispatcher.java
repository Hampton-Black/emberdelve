package dm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import dm.wire.Json;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.model.Anchor;
import dm.model.Diff;
import dm.model.Consumable;
import dm.model.Difficulty;
import dm.model.Event;
import dm.model.Mode;
import dm.model.RollRequest;
import dm.model.RollResult;
import dm.model.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates and applies tool calls. This is where invariant #7 is actually enforced — the schema
 * makes bad values hard to express, and this makes them impossible to apply.
 *
 * <p>A rejection returns a structured message the model can act on, never a stack trace. §7: the
 * model retries once, then the turn degrades to narration-only.
 */
public final class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final GameEngine engine;

    public ToolDispatcher(GameEngine engine) {
        this.engine = engine;
    }

    /**
     * @param ok             whether the call was applied
     * @param message        what the model is told — the roll outcome, or why it was rejected
     * @param diffs          what the client must be shown
     * @param rolls          dice this call produced, in the order they were thrown, so the UI can
     *                       animate them — starting combat rolls one per combatant
     * @param replacesScene  when true the caller must send a fresh Scene — a room change
     *                       replaces everything and carries no diffs (spec §9)
     */
    public record Result(boolean ok, String message, List<Diff> diffs, List<RollResult> rolls,
                         boolean replacesScene) {

        static Result rejected(String why) {
            return new Result(false, "REJECTED: " + why, List.of(), List.of(), false);
        }

        static Result applied(String message, List<Diff> diffs) {
            return new Result(true, message, diffs, List.of(), false);
        }
    }

    public Result dispatch(DmClient.ToolCall call) {
        try {
            JsonNode args = call.argumentsJson().isBlank()
                    ? Json.MAPPER.createObjectNode()
                    : Json.MAPPER.readTree(call.argumentsJson());

            return switch (call.name()) {
                case ToolSchema.ROLL_CHECK -> rollCheck(args);
                case ToolSchema.REVEAL_PROP -> revealProp(args);
                case ToolSchema.SPAWN_ENTITY -> spawnEntity(args);
                case ToolSchema.START_COMBAT -> startCombat();
                case ToolSchema.ASSERT_FACT -> assertFact(args);
                case ToolSchema.USE_EXIT -> useExit(args);
                case ToolSchema.USE_ITEM -> useItem(args);
                case ToolSchema.REST -> rest(args);
                case ToolSchema.MOVE_ENTITY -> moveEntity(args);
                case ToolSchema.TAKE_PROP -> takeProp(args);
                case ToolSchema.PLACE_MARKER -> placeMarker(args);
                default -> Result.rejected("no such tool: " + call.name());
            };
        } catch (Exception e) {
            log.warn("tool call failed: {} {}", call.name(), call.argumentsJson(), e);
            return Result.rejected("malformed arguments: " + e.getMessage());
        }
    }

    private Result rollCheck(JsonNode args) {
        var skill = parseEnum(Skill.class, args.path("skill").asText(""));
        if (skill.isEmpty()) {
            return Result.rejected("skill must be one of "
                    + names(Skill.values()) + ", got '" + args.path("skill").asText("") + "'");
        }

        var difficulty = parseEnum(Difficulty.class, args.path("difficulty").asText(""));
        if (difficulty.isEmpty()) {
            return Result.rejected("difficulty must be one of "
                    + names(Difficulty.values()) + ", got '"
                    + args.path("difficulty").asText("") + "'");
        }

        String actorId = args.path("actor_id").asText("");
        var actor = engine.state().find(actorId);
        if (actor.isEmpty()) {
            return Result.rejected("no entity '" + actorId + "' is present");
        }

        RollResult result = engine.rollCheck(actorId, skill.get(), difficulty.get());

        String message = ("%s check, DC %d. Rolled %d %+d = %d — %s. "
                + "Narrate this outcome and commit to it.").formatted(
                skill.get().name().toLowerCase(Locale.ROOT),
                difficulty.get().dc(),
                result.natural(),
                result.request().modifier(),
                result.total(),
                result.outcome());

        return new Result(true, message, engine.takePendingDiffs(), List.of(result), false);
    }

    private Result revealProp(JsonNode args) {
        String propId = args.path("prop_id").asText("");

        boolean hidden = engine.room().hiddenPropIds().contains(propId);
        if (!hidden) {
            return Result.rejected("'" + propId + "' is not a hidden prop in this room");
        }
        if (engine.state().revealedHere().contains(propId)) {
            return Result.rejected("'" + propId + "' has already been revealed");
        }

        List<Diff> diffs = engine.revealProp(propId);
        return Result.applied(
                "Revealed '" + propId + "'. It is now visible on the map — describe what they find.",
                diffs);
    }

    private Result spawnEntity(JsonNode args) {
        String kind = args.path("kind").asText("");
        // Asked for a kind this tool does not offer. The brute is engine-chosen
        // (authored spawns, ALERT, debug) — ADR-0013: the model must not pick lethality.
        if (!ToolSchema.SPAWNABLE_KINDS.contains(kind)) {
            return Result.rejected("only 'goblin' is spawnable by this tool, got '" + kind + "'");
        }

        int x = args.path("x").asInt(-1);
        int y = args.path("y").asInt(-1);
        if (!engine.isInBounds(x, y)) {
            return Result.rejected("(%d,%d) is off the %dx%d grid".formatted(
                    x, y, engine.room().width(), engine.room().height()));
        }

        boolean occupied = engine.state().entitiesHere().stream()
                .anyMatch(e -> e.x() == x && e.y() == y);
        if (occupied) {
            return Result.rejected("(" + x + "," + y + ") is already occupied — pick another square");
        }

        List<Diff> diffs = engine.spawnGoblin(x, y);
        return Result.applied(
                "A goblin is now on the grid at (%d,%d). Describe its arrival.".formatted(x, y),
                diffs);
    }

    private Result startCombat() {
        if (engine.mode() == Mode.COMBAT) {
            return Result.rejected("combat has already started");
        }
        boolean hostilePresent = engine.state().entitiesHere().stream()
                .anyMatch(e -> e.isAlive() && !e.isPlayerControlled());
        if (!hostilePresent) {
            return Result.rejected(
                    "there is nothing to fight — spawn a creature before starting combat");
        }

        var buffer = new CombatSink.Buffer();
        engine.combat().start(buffer);

        String order = engine.combat().view().order().stream()
                .map(c -> c.name() + " (" + c.initiative() + ")")
                .collect(java.util.stream.Collectors.joining(", "));

        // Bounded on purpose. Given only "describe the moment the fight starts", the prose model
        // runs on into the fight itself — observed narrating the goblin's spear opening the
        // player's thigh while the engine had already rolled that same swing a miss, and the
        // real beat then arrived and correctly described a sidestep. Two accounts of one swing,
        // one of them invented. The standing rule in dm.md forbids this; the directive has to
        // as well, because this is the sentence the model is actually answering.
        return new Result(true,
                "Combat has begun. Initiative order: " + order + ". "
                        + "Describe only the instant the fight breaks out — one or two "
                        + "sentences. Do not list the order. Do not narrate anyone's turn, "
                        + "attack, movement or wound: none of that has happened yet.",
                buffer.collectedDiffs(), buffer.collectedRolls(), false);
    }

    private Result useExit(JsonNode args) {
        String exitId = args.path("exit_id").asText("");
        String from = engine.state().roomId();
        try {
            engine.crossExit(exitId);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
        if (engine.state().ending().isPresent()) {
            return new Result(true,
                    "The party left the site. Do not describe a room they have not walked into.",
                    List.of(), List.of(), true);
        }
        // No diffs: a room change replaces everything and the caller sends a fresh Scene.
        // Spec §9.
        return new Result(true,
                "The party left " + from + " and is now in " + engine.state().roomId()
                        + ". Describe what they walk into. Do not describe " + from + " again.",
                List.of(), List.of(), true);
    }

    private Result takeProp(JsonNode args) {
        String propId = args.path("prop_id").asText("");
        try {
            List<Diff> diffs = engine.takeProp(propId);
            return Result.applied("Took '" + propId + "'. The party is carrying it.", diffs);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
    }

    private Result rest(JsonNode args) {
        String actorId = args.path("actor_id").asText("");
        boolean here = engine.state().entitiesHere().stream()
                .anyMatch(e -> e.id().equals(actorId) && e.isAlive());
        if (!here) {
            return Result.rejected("'" + actorId + "' is not in this room");
        }
        try {
            List<Diff> diffs = engine.rest(actorId);
            return Result.applied("The party rested.", diffs);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
    }

    private Result useItem(JsonNode args) {
        String actorId = args.path("actor_id").asText("");
        boolean here = engine.state().entitiesHere().stream()
                .anyMatch(e -> e.id().equals(actorId) && e.isAlive());
        if (!here) {
            return Result.rejected("'" + actorId + "' is not in this room");
        }
        String raw = args.path("item").asText("");
        var item = parseConsumable(raw);
        if (item.isEmpty()) {
            return Result.rejected("item must be potion or torch, got '" + raw + "'");
        }
        try {
            List<Diff> diffs = engine.useItem(actorId, item.get());
            return Result.applied("Spent " + raw + ".", diffs);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
    }

    private Result moveEntity(JsonNode args) {
        String actorId = args.path("actor_id").asText("");
        boolean here = engine.state().entitiesHere().stream()
                .anyMatch(e -> e.id().equals(actorId) && e.isAlive());
        if (!here) {
            return Result.rejected("'" + actorId + "' is not in this room");
        }
        int x = args.path("x").asInt(-1);
        int y = args.path("y").asInt(-1);

        var buffer = new CombatSink.Buffer();
        try {
            // The same call the click makes, so bounds, obstruction, occupancy, aliveness and —
            // in a fight — requireActive and the movement budget all apply without a second set
            // of rules that could disagree with the first.
            engine.moveTo(actorId, x, y, buffer);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
        return Result.applied("Moved '" + actorId + "' to (" + x + "," + y + ").",
                buffer.collectedDiffs());
    }

    /**
     * Records something the DM said that the board cannot hold.
     *
     * <p>The text is free-form and goes nowhere but the next prompt — it drives no roll, gates no
     * legal move, and reaches no renderer. The anchor is closed. Spec §9: that split is what
     * leaves invariant #7 standing while letting the DM improvise.
     */
    private Result assertFact(JsonNode arguments) {
        String text = arguments.path("text").asText("").strip();
        if (text.isBlank()) {
            return Result.rejected("An assertion with no text asserts nothing.");
        }

        Anchor anchor;
        String kind = arguments.path("anchor").asText("ambient");
        switch (kind) {
            case "at_square" -> {
                int x = arguments.path("x").asInt(-1);
                int y = arguments.path("y").asInt(-1);
                if (!engine.isInBounds(x, y)) {
                    return Result.rejected("(" + x + "," + y + ") is off the grid.");
                }
                anchor = new Anchor.AtSquare(x, y);
            }
            case "on" -> {
                String targetId = arguments.path("target_id").asText("");
                boolean exists = engine.state().find(targetId).isPresent()
                        || engine.room().props().stream().anyMatch(p -> p.id().equals(targetId));
                if (!exists) {
                    return Result.rejected("There is no " + targetId + " here to attach it to.");
                }
                anchor = new Anchor.On(targetId);
            }
            default -> anchor = Anchor.AMBIENT;
        }

        engine.log().append(new Event.FactAsserted(Instant.now(),
                "fact-" + UUID.randomUUID().toString().substring(0, 8),
                engine.state().roomId(), text, anchor));

        return Result.applied("Recorded: " + text, List.of());
    }

    private Result placeMarker(JsonNode arguments) {
        String text = arguments.path("text").asText("").strip();
        if (text.isBlank()) {
            return Result.rejected("A marker with no text marks nothing.");
        }
        var tag = parseEnum(dm.model.MarkerTag.class, arguments.path("tag").asText(""));
        if (tag.isEmpty()) {
            return Result.rejected("tag must be one of SCORCH, SIGIL, TRACKS, got '"
                    + arguments.path("tag").asText("") + "'");
        }
        int x = arguments.path("x").asInt(-1);
        int y = arguments.path("y").asInt(-1);
        if (!engine.isInBounds(x, y)) {
            return Result.rejected("(" + x + "," + y + ") is off the grid.");
        }
        try {
            List<Diff> diffs = engine.placeMarker(tag.get(), x, y, text);
            return Result.applied("Marked " + tag.get().name() + " at that square.", diffs);
        } catch (IllegalArgumentException e) {
            return Result.rejected(e.getMessage());
        }
    }

    private static Optional<Consumable> parseConsumable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Consumable.valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static <E extends Enum<E>> Optional<E> parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String names(Enum<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(v -> v.name().toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
