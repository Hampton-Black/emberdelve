package dm.generate;

import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.WallType;

/**
 * A room's bare dimensions and surfaces, before anything is placed in it.
 *
 * <p>Separate from {@link GeneratedRoom} because prop placement needs somewhere to place things
 * <em>onto</em>, and a half-built room record with a mutable prop list would be the obvious
 * alternative and the wrong one.
 */
public record RoomShape(
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        LightingPreset lighting
) {
}
