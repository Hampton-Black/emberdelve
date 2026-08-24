package dm.model;

/**
 * An assertion as projected — {@link Event.FactAsserted} with its timestamp dropped.
 *
 * <p>The projection of an assertion, not the record that one was made. Spec §8 caps the facts
 * shown per room; the cap drops them from the projection and never from the log.
 */
public record Fact(String id, String roomId, String text, Anchor anchor) {
}
