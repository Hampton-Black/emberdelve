package dm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgsTest {

    @Test
    void defaultsToSevenThousandSeventyAndRealDice() {
        var args = Args.parse(new String[] {});
        assertEquals(7070, args.port());
        assertFalse(args.demoMode());
        assertNull(args.generateSeed());
    }

    @Test
    void readsEveryFlag() {
        var args = Args.parse(new String[] {"--demo", "--port", "7171", "--generate", "7"});
        assertTrue(args.demoMode());
        assertEquals(7171, args.port());
        assertEquals(7L, args.generateSeed());
    }

    @Test
    void aPortWithNoNumberAfterItIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Args.parse(new String[] {"--port"}));
    }

    @Test
    void aPortThatIsNotANumberIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> Args.parse(new String[] {"--port", "seven"}));
    }

    @Test
    @DisplayName("--replay takes a path")
    void parsesReplay() {
        var args = Args.parse(new String[]{"--replay", "sessions/a.jsonl"});
        assertEquals(Path.of("sessions/a.jsonl"), args.replay().orElseThrow());
    }
}
