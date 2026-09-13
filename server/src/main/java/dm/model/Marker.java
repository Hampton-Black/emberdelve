package dm.model;

/**
 * A DM-placed glyph, as folded. {@link #text()} is prompt-only — same rule as
 * {@link Fact#text()}. The client is handed {@link MarkerView}.
 */
public record Marker(String id, String roomId, MarkerTag tag, int x, int y, String text) {

    public MarkerView toView() {
        return new MarkerView(id, tag, x, y);
    }
}
