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
        Optional<Integer> dc,

        /**
         * Which skill, on a skill check. Without it the event log records that <em>something</em>
         * was tested but not what, and the dice tray can only say "SKILL CHECK 22 vs DC 15".
         */
        Optional<Skill> skill
) {
    /** The target's AC rides in the {@code dc} slot so the roller can resolve the outcome itself. */
    public static RollRequest attack(String actorId, String targetId, int toHit, int targetAc) {
        return new RollRequest("1d20", toHit, Advantage.NORMAL, RollPurpose.ATTACK,
                actorId, Optional.of(targetId), Optional.of(targetAc), Optional.empty());
    }

    public static RollRequest damage(String actorId, String dice, int modifier) {
        return new RollRequest(dice, modifier, Advantage.NORMAL, RollPurpose.DAMAGE,
                actorId, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static RollRequest skillCheck(String actorId, Skill skill, int modifier,
                                         Difficulty difficulty) {
        return new RollRequest("1d20", modifier, Advantage.NORMAL, RollPurpose.SKILL_CHECK,
                actorId, Optional.empty(), Optional.of(difficulty.dc()), Optional.of(skill));
    }

    public static RollRequest initiative(String actorId, int modifier) {
        return new RollRequest("1d20", modifier, Advantage.NORMAL, RollPurpose.INITIATIVE,
                actorId, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
