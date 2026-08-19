package dm;

import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One session, one connection (M0). Everything the client is told goes through here.
 */
public final class WsHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);

    private final boolean demoMode;

    public WsHandler(boolean demoMode) {
        this.demoMode = demoMode;
    }

    public void register(WsConfig ws) {
        ws.onConnect(ctx -> {
            ctx.enableAutomaticPings();
            log.info("client connected: {}", ctx.sessionId());
            ctx.send("{\"type\":\"hello\",\"demoMode\":" + demoMode + "}");
        });

        ws.onMessage(ctx -> {
            log.info("recv: {}", ctx.message());
            ctx.send(ctx.message());
        });

        ws.onClose(ctx -> log.info("client disconnected: {}", ctx.sessionId()));

        ws.onError(ctx -> log.error("ws error", ctx.error()));
    }
}
