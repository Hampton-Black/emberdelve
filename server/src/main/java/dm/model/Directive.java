package dm.model;

/**
 * One clause on the narrator's directive rail. About a room (dropped on leaving it) or about
 * the party (kept). Spec §8c.
 */
public record Directive(About about, String roomId, String clause) {

    public enum About { ROOM, PARTY }

    public static Directive aboutRoom(String roomId, String clause) {
        return new Directive(About.ROOM, roomId, clause);
    }

    public static Directive aboutParty(String clause) {
        return new Directive(About.PARTY, null, clause);
    }

    public boolean isAboutRoom() {
        return about == About.ROOM;
    }
}
