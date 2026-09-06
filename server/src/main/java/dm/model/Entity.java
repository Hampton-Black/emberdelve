package dm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;

/**
 * The authoritative server-side entity. The client only ever sees the {@link EntityView}
 * projection of this — it is never told a stat block (invariant #1).
 */
public record Entity(
        String id,
        String kind,
        String name,
        int ac,
        int hp,
        int maxHp,
        int toHit,
        String damageDice,
        int damageModifier,
        int speedFeet,
        /** DEX, and the only ability score the engine ever consults. Combat rolls initiative. */
        int initiativeModifier,
        /** Which room it is standing in. Scoping lives on the entity so there is nothing to desync. */
        String roomId,
        int x,
        int y,
        boolean isPlayerControlled,
        Map<Skill, Integer> skillModifiers
) {
    public Entity {
        var ordered = new java.util.EnumMap<Skill, Integer>(Skill.class);
        ordered.putAll(skillModifiers);
        skillModifiers = java.util.Collections.unmodifiableMap(ordered);
    }

    @JsonIgnore
    public boolean isAlive() {
        return hp > 0;
    }

    /** Squares of movement per turn. Five feet to a square. */
    public int speedSquares() {
        return speedFeet / 5;
    }

    public int skillModifier(Skill skill) {
        return skillModifiers.getOrDefault(skill, 0);
    }

    public Entity movedTo(int newX, int newY) {
        return new Entity(id, kind, name, ac, hp, maxHp, toHit, damageDice, damageModifier,
                speedFeet, initiativeModifier, roomId, newX, newY, isPlayerControlled,
                skillModifiers);
    }

    /** Through a door. The only thing that changes which room an entity is in. */
    public Entity movedToRoom(String newRoomId, int newX, int newY) {
        return new Entity(id, kind, name, ac, hp, maxHp, toHit, damageDice, damageModifier,
                speedFeet, initiativeModifier, newRoomId, newX, newY, isPlayerControlled,
                skillModifiers);
    }

    public Entity withHp(int newHp) {
        return new Entity(id, kind, name, ac, Math.clamp(newHp, 0, maxHp), maxHp, toHit,
                damageDice, damageModifier, speedFeet, initiativeModifier, roomId, x, y,
                isPlayerControlled, skillModifiers);
    }

    public Entity damaged(int amount) {
        return withHp(hp - amount);
    }

    public EntityView toView() {
        return new EntityView(id, kind, name, x, y, hp, maxHp, isPlayerControlled);
    }

    /** Chebyshev distance — diagonals cost one square, as in 5e's simplified grid rules. */
    public int distanceTo(Entity other) {
        return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
    }

    public boolean isAdjacentTo(Entity other) {
        return distanceTo(other) <= 1;
    }
}
