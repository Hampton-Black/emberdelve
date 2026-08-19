package dm;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);
    private static final int PORT = 7070;

    public static void main(String[] args) {
        boolean demoMode = java.util.Arrays.asList(args).contains("--demo");

        var app = Javalin.create(config -> {
            // The Vite dev server lives on another origin; M0 is localhost-only and unauthenticated.
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.get("/health", ctx -> ctx.result("ok"));

        var handler = new WsHandler(demoMode);
        app.ws("/ws", handler::register);

        app.start(PORT);
        log.info("Emberdelve server on :{}{}", PORT, demoMode ? " (demo mode: scripted dice)" : "");
    }

    private App() {
    }
}
