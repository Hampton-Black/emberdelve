package dm.model;

/**
 * Where an asserted fact lives.
 *
 * <p>Spec §9: a fact is a free-form description that only ever enters the prompt, plus a handle
 * that is entirely closed. This is the handle. Most facts are {@link Ambient} — a smell, a
 * temperature, a sound — and can never appear on the board. The other two can, whenever the
 * renderer is built for it.
 *
 * <p>Present from the first event written, because retrofitting an anchor onto a log full of
 * anchorless facts is a migration and adding the field now is free.
 */
public sealed interface Anchor {

    record Ambient() implements Anchor {}

    record AtSquare(int x, int y) implements Anchor {}

    /** On an entity or a prop, by id. Follows whatever it is attached to. */
    record On(String targetId) implements Anchor {}

    Ambient AMBIENT = new Ambient();
}
