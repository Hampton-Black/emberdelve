package dm.engine;

import dm.content.RoomDefinition;
import dm.model.Exit;
import dm.model.FloorType;
import dm.model.LightingPreset;
import dm.model.WallType;

import java.util.List;

/**
 * Bare rooms for the tests that only care about shape and the exit graph.
 *
 * <p>Synthetic on purpose — {@code content/} holds two authored rooms and adding a third is an
 * {@code AGENTS.md} anti-goal. A chain or a cycle here costs nothing and touches no content.
 */
final class SyntheticRooms {

    private SyntheticRooms() {
    }

    static RoomDefinition room(String id, int width, int height, Exit... exits) {
        return new RoomDefinition(id, id, width, height, FloorType.STONE, WallType.STONE,
                LightingPreset.DARK, List.of(), List.of(exits),
                new RoomDefinition.StartPositions(
                        List.of(new RoomDefinition.Point(1, 1)),
                        new RoomDefinition.Point(1, 1)),
                new RoomDefinition.DmNotes("o", "s", null, null, null));
    }
}
