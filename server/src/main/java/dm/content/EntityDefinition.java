package dm.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dm.model.Entity;
import dm.model.Skill;

import java.util.Map;

/** A stat block as it appears on disk. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityDefinition(
        String id,
        String kind,
        String name,
        int ac,
        int maxHp,
        int toHit,
        String damageDice,
        int damageModifier,
        int speedFeet,
        Map<String, Integer> abilityModifiers,
        Map<Skill, Integer> skillModifiers,
        boolean isPlayerControlled,
        String description,
        String voice
) {
    /** Instantiate at full health at the given square. */
    public Entity spawn(String entityId, int x, int y) {
        return new Entity(entityId, kind, name, ac, maxHp, maxHp, toHit, damageDice,
                damageModifier, speedFeet, x, y, isPlayerControlled,
                skillModifiers == null ? Map.of() : skillModifiers);
    }
}
