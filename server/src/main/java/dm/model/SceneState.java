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
        /**
         * Squares the player can see are solid, so the board can say so before they click.
         *
         * <p>Shipped rather than derived, for the same reason {@code CombatView} ships
         * {@code legalMoves}: the client owns no movement rule (invariant #1). It cannot be read
         * off {@code props} either — an alcove is a prop and you can walk into it.
         *
         * <p>Visible props only. A hidden prop that blocks would otherwise put a marker on the
         * board exactly where a secret is. The server refuses the move either way; this is the
         * hint, not the rule.
         */
        List<Square> blocked,
        /** Adjacent rooms as floor and walls only — spec §8b. Empty when nothing is loaded. */
        List<RoomOutline> neighbours,
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
                visibleProps(), exits, blocked, neighbours, entities, lighting, mode, combat);
    }
}
