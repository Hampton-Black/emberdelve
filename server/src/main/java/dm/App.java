package dm;

import dm.content.ContentLoader;
import dm.engine.DiceRoller;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.engine.ScriptedDiceRoller;
import dm.repo.InMemoryGameRepository;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final int PORT = 7070;

    /**
     * Rolls the §2 acceptance script needs to land the same way every run: a strong athletics
     * check to shift the lid, a found alcove, then a fight the fighter wins on the fourth swing.
     */
    private static final List<Integer> DEMO_SCRIPT =
            List.of(17, 14, 12, 8, 19, 6, 15, 11, 20, 5, 13, 9);

    public static void main(String[] args) {
        boolean demoMode = Arrays.asList(args).contains("--demo");

        DiceRoller dice = demoMode
                ? new ScriptedDiceRoller(DEMO_SCRIPT)
                : new RandomDiceRoller();

        var content = new ContentLoader();
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(content, repo, dice);
        engine.start();

        var app = Javalin.create(config -> {
            // The Vite dev server lives on another origin; M0 is localhost-only and unauthenticated.
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("ok"));

        var handler = new WsHandler(engine, demoMode);
        app.ws("/ws", handler::register);

        app.start(PORT);
        log.info("Emberdelve on :{} — room '{}', {} entities{}",
                PORT,
                engine.room().name(),
                repo.entities().size(),
                demoMode ? ", demo dice" : "");
    }

    private App() {
    }
}
