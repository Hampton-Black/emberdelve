package dm.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.PropType;
import dm.model.WallType;

import java.util.List;

/**
 * A generator kit as it appears on disk: the closed set of tiles and props a generated room
 * may draw from.
 *
 * <p>This is the first real content-as-data schema in the project and its shape is meant to
 * outlive M1 — the rules engine will load monsters and spells the same way. It is deliberately
 * a palette and not a template: the kit says what <em>may</em> appear and in what quantity,
 * and the generator decides what does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KitDefinition(
        String kitId,
        List<FloorType> floors,
        List<WallType> walls,
        List<LightingPreset> lightings,
        IntRange size,
        List<PropEntry> props
) {

    /** Inclusive on both ends. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IntRange(int min, int max) {
    }

    /**
     * How many of a prop type a room may hold.
     *
     * @param unique whether a room may hold at most one, regardless of {@code maxCount} — a
     *               chamber with three sarcophagi reads as a warehouse, not a crypt
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PropEntry(PropType type, int minCount, int maxCount, boolean unique) {
    }
}
