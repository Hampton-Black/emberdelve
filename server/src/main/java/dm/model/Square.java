package dm.model;

/** A grid square. Exists so lists of legal moves are not lists of untyped int pairs. */
public record Square(int x, int y) {
}
