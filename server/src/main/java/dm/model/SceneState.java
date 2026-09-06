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
        /** Ways out, so the client knows which floor squares are doors. */
        List<Exit> exits,
        List<EntityView> entities,
        LightingPreset lighting,
        Mode mode,
        /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
        CombatView combat
) {
    /** Props the player can currently see. Hidden ones stay server-side until revealed. */
    public List<Prop> visibleProps() {
        return props.stream().filter(p -> !p.hidden()).toList();
    }

    /** The scene as the client should first see it — hidden props stripped out entirely. */
    public SceneState asSeenByPlayer() {
        return new SceneState(roomId, width, height, floorType, wallType,
                visibleProps(), exits, entities, lighting, mode, combat);
    }
}
