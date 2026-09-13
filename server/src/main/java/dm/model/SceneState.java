package dm.model;

import java.util.List;

/**
 * The full picture of the world as far as the client is allowed to see it. Sent once on connect
 * and on any change large enough that a diff would be silly; everything smaller goes over the
 * wire as a {@link Diff}.
 *
 * <p>{@code rooms} carries every room the party has visited plus every room visible through a
 * current room exit — the current room included, in the same {@link RoomView} shape as everything
 * else it lists. {@code entities}, {@code blocked}, {@code mode} and {@code combat} stay
 * session-level: they describe the room the party is standing in, not the whole set. See
 * {@code GameEngine.scene()} for how the room set is decided.
 */
public record SceneState(
        String roomId,
        List<RoomView> rooms,
        List<EntityView> entities,
        /**
         * Squares the player can see are solid, so the board can say so before they click.
         *
         * <p>Shipped rather than derived, for the same reason {@code CombatView} ships
         * {@code legalMoves}: the client owns no movement rule (invariant #1). It cannot be read
         * off a room's props either — an alcove is a prop and you can walk into it.
         *
         * <p>Visible props only. A hidden prop that blocked would otherwise put a marker on the
         * board exactly where a secret is. The server refuses the move either way; this is the
         * hint, not the rule.
         */
        List<Square> blocked,
        Mode mode,
        /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
        CombatView combat,
        /** Consumable counts for the exploration bar. Zeroes included. Spec §10. */
        int potions,
        int torches,
        int rope,
        /**
         * Whether spending a torch would do anything right now — not a segment count on the wire.
         * Spec §10.
         */
        boolean canSpendTorch,
        /** Whether resting is legal right now — not combat and no living hostile here. */
        boolean canRest,
        /**
         * How much light the party is casting. Derived from LIGHT filled, never stored beside
         * the clock. Never a segment count or a radius. Spec §7a, §10.
         */
        PartyLight partyLight
) {
    /** The room named by {@link #roomId()}, in the same list as everything else. */
    public RoomView currentRoom() {
        return rooms.stream()
                .filter(r -> r.roomId().equals(roomId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Current room '" + roomId + "' missing from its own scene"));
    }
}
