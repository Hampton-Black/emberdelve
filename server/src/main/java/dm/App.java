package dm;

import dm.ai.Config;
import dm.ai.DmService;
import dm.ai.VeniceDmClient;
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
        var config = Config.load();

        DiceRoller dice = demoMode ? new ScriptedDiceRoller(DEMO_SCRIPT) : new RandomDiceRoller();

        var content = new ContentLoader();
        var repo = new InMemoryGameRepository();
        var engine = new GameEngine(content, repo, dice);
        engine.start();

        // Everything up to T5 runs without a key; only narration needs one.
        DmService dm = null;
        if (config.has("VENICE_API_KEY")) {
            // Two models, two jobs. The fast one decides mechanics and puts dice on the table;
            // the strong one writes, while those dice are still animating. See ai/DmService.
            // Deliberately does NOT fall back to DM_MODEL: the whole point is that the
            // mechanics model differs from the prose one, and inheriting a single DM_MODEL
            // would silently collapse the split back into one slow model doing both jobs.
            String toolModel = config.get("DM_MODEL_TOOLS", "qwen3-next-80b");
            String proseModel = config.get("DM_MODEL_PROSE",
                    config.get("DM_MODEL", "claude-opus-5"));

            // The mechanics call is on the critical path and must fail fast; the prose call
            // runs behind dice animation and can afford to wait.
            dm = new DmService(
                    new VeniceDmClient(config, toolModel, java.time.Duration.ofSeconds(20)),
                    new VeniceDmClient(config, proseModel, java.time.Duration.ofSeconds(90)),
                    engine,
                    content.prompt("dm-tools"),
                    content.prompt("dm"));
        } else {
            log.warn("VENICE_API_KEY not set — narration disabled. "
                    + "Copy .env.example to .env to enable the DM.");
        }

        var app = Javalin.create(cfg -> {
            // The Vite dev server lives on another origin; M0 is localhost-only and unauthenticated.
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("ok"));

        var handler = new WsHandler(engine, dm, demoMode);
        app.ws("/ws", handler::register);

        // Warm-check both endpoints off the startup path. A degraded Venice model looks exactly
        // like a slow app from the inside, and this turns an hour of debugging into one log line.
        if (dm != null) {
            dm.warmCheck();
        }

        app.start(PORT);
        log.info("Emberdelve on :{} — room '{}', {} entities, dm={}{}",
                PORT,
                engine.room().name(),
                repo.entities().size(),
                dm == null ? "disabled" : dm.modelId(),
                demoMode ? ", demo dice" : "");
    }

    private App() {
    }
}
