package dm.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * One configured mapper for the whole process.
 *
 * <p>The Jdk8Module is not optional: {@code RollRequest} carries {@code Optional} fields per §6,
 * and without it every roll silently fails to serialise and never reaches the client.
 */
public final class Json {

    /**
     * The JavaTimeModule is as load-bearing as the Jdk8Module beside it: every event carries an
     * {@code Instant}, and without it a session log serialises as a nest of numbers that will not
     * read back.
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature
                    .WRITE_DATES_AS_TIMESTAMPS);

    private Json() {
    }
}
