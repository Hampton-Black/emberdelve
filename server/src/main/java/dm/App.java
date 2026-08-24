package dm;

import dm.ai.Config;
import dm.ai.TtsClient;
import dm.ai.DmService;
import dm.ai.VeniceDmClient;
import dm.content.ContentLoader;
import dm.content.RoomDefinition;
import dm.engine.DiceRoller;
import dm.engine.GameEngine;
import dm.engine.RandomDiceRoller;
import dm.engine.ScriptedDiceRoller;
import dm.replay.ReplayRunner;
import dm.generate.RoomDresser;
import dm.generate.RoomDumper;
import dm.generate.RoomGenerator;
import dm.generate.RoomSource;
import dm.model.Entity;
import dm.model.Event;
import dm.state.EventLog;
import dm.state.SessionWriter;
import dm.state.WorldState;
import dm.wire.Json;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /**
     * Rolls the §2 acceptance script needs to land the same way every run: a strong athletics
     * check to shift the lid, a found alcove, then a fight the fighter wins on the fourth swing.
     */
    private static final List<Integer> DEMO_SCRIPT =
            List.of(17, 14, 12, 8, 19, 6, 15, 11, 20, 5, 13, 9);

    public static void main(String[] args) {
        var cli = Args.parse(args);
        if (cli.replay().isPresent()) {
            var result = ReplayRunner.replay(cli.replay().get());
            log.info("replayed {} events: {}", result.compared(),
                    result.matched() ? "identical" : result.firstDivergence());
            System.exit(result.matched() ? 0 : 1);
        }
        boolean demoMode = cli.demoMode();
        var config = Config.load();

        DiceRoller dice = demoMode ? new ScriptedDiceRoller(DEMO_SCRIPT) : new RandomDiceRoller();

        var content = new ContentLoader();
        var writer = SessionWriter.open(Path.of("sessions"));
        var eventLog = new EventLog(writer);
        Runtime.getRuntime().addShutdownHook(new Thread(writer::close));

        // --generate <seed> boots into a procedurally generated room instead of the crypt.
        // The crypt stays the default: it is the room every M0 measurement was taken in.
        RoomDefinition room;
        if (cli.generateSeed() == null) {
            room = RoomSource.authored(content, "crypt");
        } else if (config.has("VENICE_API_KEY")) {
            // The dress pass reads as writing, but what it must actually emit is a JSON object
            // with a fixed shape — which is the tools model's skill, not the prose model's.
            // Measured on seed 7, four samples each: venice-uncensored-role-play produced
            // unparseable JSON 4/4 (a string opened with " and closed with ', a stray 'あ', a
            // bad escape) and fell back to "An Unnamed Chamber" every time; qwen3-next-80b
            // parsed 3/3. A prose model tuned for roleplay cannot hold a quote character.
            room = RoomSource.generated(content, new RoomDresser(
                    new VeniceDmClient(config, config.get("DM_MODEL_TOOLS", "qwen3-next-80b"),
                            java.time.Duration.ofSeconds(30)),
                    content.prompt("dress-room")), "crypt", cli.generateSeed());
        } else {
            // Undressed but playable — the generator half needs no key, and a room with no prose
            // is more useful than a refusal to boot while tuning layout. The dump is logged here
            // too: this is the path that exists for tuning layout, and it is the one place the
            // grid would otherwise never be seen.
            log.warn("VENICE_API_KEY not set — generating an undressed room.");
            var generated = new RoomGenerator(content).generate("crypt", cli.generateSeed());
            log.info("generated room, seed {}:\n{}", cli.generateSeed(), RoomDumper.dump(generated));
            room = generated.toRoomDefinition();
        }

        var engine = new GameEngine(content, eventLog, dice, room);
        eventLog.append(new Event.SessionStarted(Instant.now(), Event.SCHEMA_VERSION,
                0L, // the dungeon seed lands here when navigation does
                config.orElse("DM_MODEL_TOOLS", "none"),
                config.orElse("DM_MODEL_PROSE", "none")));
        // The dress pass lands here when navigation makes it per-room. A generated
        // room currently bakes Dressing into RoomDefinition and does not carry a
        // Dressing object, so there is nothing to append until then.
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
                    content.prompt("dm"),
                    content.prompt("dm-reconcile"));
        } else {
            log.warn("VENICE_API_KEY not set — narration disabled. "
                    + "Copy .env.example to .env to enable the DM.");
        }

        var app = Javalin.create();

        app.get("/health", ctx -> ctx.result("ok"));

        // Speech is HTTP rather than websocket on purpose. A minute of narration is a few hundred
        // kilobytes, and pushing that down the same socket the diffs use would put it behind — or
        // in front of — a click-to-move that has a 100ms budget. It also lets the browser start
        // playing before the whole clip has arrived.
        TtsClient tts = null;
        if (config.has("ELEVENLABS_API_KEY")) {
            tts = new TtsClient(
                    config.require("ELEVENLABS_API_KEY"),
                    config.require("ELEVENLABS_VOICE_NARRATOR"),
                    config.require("ELEVENLABS_VOICE_GOBLIN"),
                    // Falls back to the speaker id: [[goblin]] is attributed before reconcile
                    // has spawned the creature, and treating the unresolved id as a kind is
                    // what keeps the arrival line in the goblin's voice.
                    id -> kindForVoice(engine.state(), id));
            final TtsClient voice = tts;

            app.post("/tts", ctx -> {
                var body = Json.MAPPER.readTree(ctx.body());
                var audio = voice.speak(
                        body.path("speakerId").asText("narrator"),
                        body.path("text").asText(""));

                if (audio.isEmpty()) {
                    // The client falls back to the browser's own voice on anything but a 200,
                    // so the status is the whole message. Nothing here is worth a body.
                    ctx.status(502);
                    return;
                }
                ctx.contentType("audio/mpeg").result(audio.get());
            });
        } else {
            log.warn("ELEVENLABS_API_KEY not set — narration falls back to the browser's own "
                    + "speech synthesis.");
        }

        var handler = new WsHandler(engine, dm, demoMode, tts != null);
        app.ws("/ws", handler::register);

        // Warm-check both endpoints off the startup path. A degraded Venice model looks exactly
        // like a slow app from the inside, and this turns an hour of debugging into one log line.
        if (dm != null) {
            dm.warmCheck();
        }

        // Loopback only. This is a local DM, not a network service — and it stays that way
        // until session scoping and auth exist, which is the multiplayer milestone's work.
        app.start("127.0.0.1", cli.port());
        log.info("Emberdelve on 127.0.0.1:{} — room '{}', {} entities, dm={}{}",
                cli.port(),
                engine.room().name(),
                engine.state().entities().size(),
                dm == null ? "disabled" : dm.modelId(),
                demoMode ? ", demo dice" : "");
    }

    /**
     * Kind for TTS. Falls back to the speaker id itself so a {@code [[goblin]]} line that
     * arrives before reconcile has spawned the creature still gets the goblin voice.
     */
    static String kindForVoice(WorldState state, String speakerId) {
        return state.find(speakerId).map(Entity::kind).orElse(speakerId);
    }

    private App() {
    }
}
