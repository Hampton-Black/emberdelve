package dm.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HelloTest {

    @Test
    void carriesWhetherTheDmIsConfigured() throws Exception {
        String json = Json.MAPPER.writeValueAsString(new ServerMessage.Hello(true, false, true));
        assertTrue(json.contains("\"dm\":true"), json);
        assertTrue(json.contains("\"voice\":false"), json);
        assertTrue(json.contains("\"demoMode\":true"), json);
    }
}
