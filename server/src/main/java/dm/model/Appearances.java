package dm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import dm.wire.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed set of kit looks. {@link PropType} says what a prop <em>is</em>; this says how it
 * is drawn. The server ships the id; the client never chooses one (invariant #1).
 */
public final class Appearances {

    public record Spec(String id, PropType type, String kit, String file,
                       double height, double footprint) {

        public Path meshFile() {
            String ext = kit.startsWith("kaykit_") ? ".gltf" : ".glb";
            return kitRoot().resolve(kit).resolve(file + ext);
        }

        /** Path the Godot mesh-prop loader understands: {@code dungeon/Chest}, {@code ruins/Pot1}. */
        public String meshPath() {
            String prefix = "dungeon_props".equals(kit) ? "dungeon" : kit;
            return prefix + "/" + file;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(PropType type, String kit, String file, double height, double footprint) {
    }

    private static final Map<String, Spec> BY_ID = load();

    private Appearances() {
    }

    public static List<Spec> all() {
        return List.copyOf(BY_ID.values());
    }

    public static List<Spec> ofType(PropType type) {
        var out = new ArrayList<Spec>();
        for (var spec : BY_ID.values()) {
            if (spec.type() == type) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    public static Spec spec(String id) {
        var spec = BY_ID.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("unknown appearance: " + id);
        }
        return spec;
    }

    public static boolean isKnown(PropType type, String id) {
        var spec = BY_ID.get(id);
        return spec != null && spec.type() == type;
    }

    public static boolean requiresAppearance(PropType type) {
        return switch (type) {
            case CONTAINER, STATUE, FURNITURE, REMAINS, SCENERY -> true;
            case SARCOPHAGUS, BRAZIER, PILLAR, RUBBLE, ALCOVE, DOOR -> false;
        };
    }

    /** First catalog entry for the type, or empty when the type has a dedicated scene. */
    public static String defaultFor(PropType type) {
        for (var spec : BY_ID.values()) {
            if (spec.type() == type) {
                return spec.id();
            }
        }
        return "";
    }

    public static String orDefault(PropType type, String appearance) {
        if (appearance != null && !appearance.isBlank()) {
            return appearance;
        }
        return defaultFor(type);
    }

    public static Path kitRoot() {
        Path fromServer = Path.of("..", "godot", "world", "kits");
        if (Files.isDirectory(fromServer)) {
            return fromServer.toAbsolutePath().normalize();
        }
        Path fromRepo = Path.of("godot", "world", "kits");
        if (Files.isDirectory(fromRepo)) {
            return fromRepo.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("cannot find godot/world/kits from " + Path.of("").toAbsolutePath());
    }

    private static Map<String, Spec> load() {
        try (var in = Appearances.class.getResourceAsStream("/content/appearances.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing content file: /content/appearances.json");
            }
            Map<String, Entry> raw = Json.MAPPER.readValue(in, new TypeReference<>() {
            });
            var out = new LinkedHashMap<String, Spec>();
            raw.forEach((id, entry) -> out.put(id, new Spec(
                    id, entry.type(), entry.kit(), entry.file(), entry.height(), entry.footprint())));
            return Collections.unmodifiableMap(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed content file: /content/appearances.json", e);
        }
    }
}
