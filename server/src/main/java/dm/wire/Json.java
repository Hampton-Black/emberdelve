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

    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private Json() {
    }
}
