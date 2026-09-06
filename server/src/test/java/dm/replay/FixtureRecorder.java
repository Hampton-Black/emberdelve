package dm.replay;

import dm.content.ContentLoader;
import dm.engine.CombatSink;
import dm.engine.GameEngine;
import dm.engine.ScriptedDiceRoller;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.SessionWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Rewrites the checked-in replay fixture at the current schema.
 *
 * <p>The fixture is refused rather than migrated whenever {@link Event#SCHEMA_VERSION} moves —
 * that is the rule, and it means the file has to be producible on demand instead of being a blob
 * nobody can regenerate. Scripted dice and no model, so the same command gives the same file.
 *
 * <p>Run it with:
 * {@code cd server && ./gradlew -q recordFixture}
 */
public final class FixtureRecorder {

    static final Path FIXTURE = Path.of("src/test/resources/sessions/crypt-fight.jsonl");

    /**
     * Fighter initiative 1, goblin 20 — the goblin goes first, which is the case the fixture
     * exists to cover. Then the goblin hits for 4, and the fighter answers and kills.
     */
    private static final ScriptedDiceRoller SCRIPT =
            new ScriptedDiceRoller(1, 20, 19, 4, 14, 4);

    private FixtureRecorder() {
    }

    public static void main(String[] args) throws IOException {
        Path written = record(Files.createTempDirectory("fixture"));
        Files.createDirectories(FIXTURE.getParent());
        Files.copy(written, FIXTURE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + FIXTURE.toAbsolutePath());
    }

    /** The session itself, so a test can build one without touching the checked-in file. */
    static Path record(Path directory) {
        var writer = SessionWriter.open(directory);
        var log = new EventLog(writer);
        log.append(new Event.SessionStarted(
                Instant.now(), Event.SCHEMA_VERSION, 0L, "none", "none"));

        var engine = new GameEngine(new ContentLoader(), log, SCRIPT);
        var sink = new CombatSink.Buffer();
        engine.start();
        engine.moveTo("fighter", 6, 2, sink);
        engine.revealProp("alcove");
        engine.spawnGoblin(6, 6);
        engine.combat().start(sink);
        engine.combat().runAutomaticTurns(sink, () -> { });
        engine.combat().attack("fighter", "goblin", sink);

        writer.close();
        return writer.path();
    }
}
