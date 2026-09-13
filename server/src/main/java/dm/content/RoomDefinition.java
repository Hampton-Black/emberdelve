package dm.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dm.model.Exit;
import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.Prop;
import dm.model.PropType;
import dm.model.WallType;

import java.util.List;
import java.util.Optional;

/**
 * A hand-authored room as it appears on disk. Carries more than the client needs — the
 * prose fields exist for the DM prompt, not for the renderer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomDefinition(
        String roomId,
        String name,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        LightingPreset lighting,
        List<PropDefinition> props,
        List<Exit> exits,
        StartPositions startPositions,
        DmNotes dmNotes,
        Fires fires
) {

    /**
     * Defensive, and null-tolerant because a room file written before exits existed omits the
     * key entirely and Jackson hands us null rather than an empty list.
     */
    public RoomDefinition {
        exits = exits == null ? List.of() : List.copyOf(exits);
    }

    public RoomDefinition(
            String roomId, String name, int width, int height,
            FloorType floorType, WallType wallType, LightingPreset lighting,
            List<PropDefinition> props, List<Exit> exits,
            StartPositions startPositions, DmNotes dmNotes) {
        this(roomId, name, width, height, floorType, wallType, lighting, props, exits,
                startPositions, dmNotes, null);
    }

    /**
     * A room's own fires, and the one step they ever take. Null when the room never moves.
     * Spec §7c.
     */
    public record Fires(String lit, LightingPreset to, String moved) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropDefinition(
            String id,
            PropType type,
            int x,
            int y,
            int rotation,
            boolean hidden,
            String description,
            String revealHint,
            String contains,
            java.util.List<String> actions
    ) {
        public Prop toProp() {
            return new Prop(id, type, x, y, rotation, hidden, actions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StartPositions(List<Point> party, Point goblinSpawn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(int x, int y) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * @param theSarcophagus       while Vessk is still inside it
     * @param theSarcophagusOpened once he is out — see the swap in {@code DmService.worldState}
     */
    public record DmNotes(String overview, String sensory, String theSarcophagus,
                          String theSarcophagusOpened, String theDoor) {
    }

    public List<Prop> toProps() {
        return props.stream().map(PropDefinition::toProp).toList();
    }

    public PropDefinition prop(String id) {
        return props.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such prop: " + id));
    }

    /** The exit standing on this square, if one does. */
    public Optional<Exit> exitAt(int x, int y) {
        return exits.stream().filter(e -> e.x() == x && e.y() == y).findFirst();
    }

    /**
     * Whether a solid prop stands on this square.
     *
     * <p>Terrain belongs to the room, not to combat: walking into the sarcophagus is impossible
     * whether or not anyone has rolled initiative, and a rule that only existed inside
     * {@code CombatEngine} would let the player stroll through it between fights.
     */
    public boolean isObstructed(int x, int y) {
        return props.stream()
                .anyMatch(p -> p.x() == x && p.y() == y && p.type().blocksMovement());
    }

    /** The ids the {@code reveal_prop} tool is allowed to name right now — a closed set. */
    public List<String> hiddenPropIds() {
        return props.stream().filter(PropDefinition::hidden).map(PropDefinition::id).toList();
    }
}
