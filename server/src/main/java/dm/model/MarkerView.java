package dm.model;

/**
 * A marker as the client may see it: id, tag, square. Never the free-form text.
 */
public record MarkerView(String id, MarkerTag tag, int x, int y) {
}
