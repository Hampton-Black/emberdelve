package dm.model;

import java.util.List;

/**
 * The full picture of a room. Sent once on connect and on any change large enough
 * that a diff would be silly; everything smaller goes over the wire as a {@link Diff}.
 */
public record SceneState(
        String roomId,
        int width,
        int height,
        FloorType floorType,
        WallType wallType,
        List<Prop> props,
        List<EntityView> entities,
        LightingPreset lighting
) {
    /** Props the player can currently see. Hidden ones stay server-side until revealed. */
    public List<Prop> visibleProps() {
        return props.stream().filter(p -> !p.hidden()).toList();
    }

    /** The scene as the client should first see it — hidden props stripped out entirely. */
    public SceneState asSeenByPlayer() {
        return new SceneState(roomId, width, height, floorType, wallType,
                visibleProps(), entities, lighting);
    }
}
