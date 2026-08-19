package dm.model;

import java.util.Optional;

/** What someone is trying to do, not a notation string to be parsed. */
public record RollRequest(
        String dice,
        int modifier,
        Advantage advantage,
        RollPurpose purpose,
        String actorId,
        Optional<String> targetId,
        Optional<Integer> dc
) {
    /** The target's AC rides in the {@code dc} slot so the roller can resolve the outcome itself. */
    public static RollRequest attack(String actorId, String targetId, int toHit, int targetAc) {
        return new RollRequest("1d20", toHit, Advantage.NORMAL, RollPurpose.ATTACK,
                actorId, Optional.of(targetId), Optional.of(targetAc));
    }

    public static RollRequest damage(String actorId, String dice, int modifier) {
        return new RollRequest(dice, modifier, Advantage.NORMAL, RollPurpose.DAMAGE,
                actorId, Optional.empty(), Optional.empty());
    }

    public static RollRequest skillCheck(String actorId, int modifier, Difficulty difficulty) {
        return new RollRequest("1d20", modifier, Advantage.NORMAL, RollPurpose.SKILL_CHECK,
                actorId, Optional.empty(), Optional.of(difficulty.dc()));
    }

    public static RollRequest initiative(String actorId, int modifier) {
        return new RollRequest("1d20", modifier, Advantage.NORMAL, RollPurpose.INITIATIVE,
                actorId, Optional.empty(), Optional.empty());
    }
}
