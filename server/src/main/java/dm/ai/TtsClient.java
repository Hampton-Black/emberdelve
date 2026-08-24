package dm.ai;

import dm.wire.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Segment in, audio bytes out.
 *
 * <p>Server-side because the key must never reach the browser — that is the whole reason this
 * class exists rather than the client calling ElevenLabs directly. The browser asks this process
 * for audio and never learns who made it, which is also what lets the voice be swapped without
 * the client changing.
 *
 * <p>Flash v2.5 rather than a better-sounding model on purpose. The §11 budget is 1.5s from
 * keypress to the first spoken word, and most of that is already spent by the time a sentence
 * exists to say — what is left is a single round trip, and Flash is the one that fits in it.
 *
 * <p>Two voices, as §4 shortcut 9 says: a narrator and a goblin. Anything that is not the goblin
 * is the narrator, including the fighter — a third voice is M6's problem.
 */
public final class TtsClient {

    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);

    private static final String BASE = "https://api.elevenlabs.io/v1/text-to-speech/";
    private static final String MODEL = "eleven_flash_v2_5";
    private static final String FORMAT = "mp3_44100_128";

    /** Generous next to Flash's own latency: this covers a cold connection, not a slow model. */
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String apiKey;
    private final String narratorVoice;
    private final String goblinVoice;
    /**
     * Entity id to entity kind. Injected rather than looked up because this client is handed a
     * speaker id by an HTTP endpoint and has no world to consult.
     */
    private final UnaryOperator<String> kindOf;

    public TtsClient(String apiKey, String narratorVoice, String goblinVoice,
                     UnaryOperator<String> kindOf) {
        this.apiKey = apiKey;
        this.narratorVoice = narratorVoice;
        this.goblinVoice = goblinVoice;
        this.kindOf = kindOf;
    }

    /**
     * Which of the two voices says this.
     *
     * <p>Keyed on kind rather than on the id. There is one goblin today and its id is the string
     * "goblin", so matching the id worked — and would have stopped working, silently, the first
     * time a dungeon held two and their ids became "goblin-1" and "goblin-2". A wrong voice
     * raises nothing and looks like nothing; you find it by ear, mid-session.
     *
     * <p>Package-private so the choice can be tested without a key or a network.
     */
    String voiceFor(String speakerId) {
        return "goblin".equalsIgnoreCase(kindOf.apply(speakerId)) ? goblinVoice : narratorVoice;
    }

    /**
     * Synthesises one line, or returns empty if it could not.
     *
     * <p>Never throws. A voice that fails is a line the player reads instead of hears, and the
     * client falls back to the browser's own speech synthesis — losing the performance is bad,
     * losing the turn would be worse (shortcut #13).
     */
    public Optional<byte[]> speak(String speakerId, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        try {
            String body = Json.MAPPER.writeValueAsString(Map.of(
                    "text", text,
                    "model_id", MODEL));

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + voiceFor(speakerId) + "?output_format=" + FORMAT))
                    .timeout(TIMEOUT)
                    .header("xi-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            long started = System.nanoTime();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long ms = (System.nanoTime() - started) / 1_000_000;

            if (response.statusCode() != 200) {
                // The body carries the reason and never the key. Worth logging in full: a 402 on
                // a library voice and a 401 on a bad key are the same silence from the outside.
                log.warn("TTS {} for {} — {}", response.statusCode(), speakerId,
                        new String(response.body()).strip());
                return Optional.empty();
            }

            log.info("TTS      {}ms  {} bytes  {}  <- {} chars",
                    ms, response.body().length, speakerId, text.length());
            return Optional.of(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("TTS failed for {}", speakerId, e);
            return Optional.empty();
        }
    }
}
