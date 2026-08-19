package dm.model;

/** What the client is allowed to know about an entity. Never the full stat block. */
public record EntityView(
        String id,
        String kind,
        String name,
        int x,
        int y,
        int hp,
        int maxHp,
        boolean isPlayerControlled
) {
    public boolean isAlive() {
        return hp > 0;
    }
}
