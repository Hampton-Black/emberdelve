package dm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionGuardTest {

    @Test
    void theFirstClientGetsTheTable() {
        var guard = new SessionGuard();
        assertTrue(guard.claim("a"));
    }

    @Test
    void aSecondClientIsRefused() {
        var guard = new SessionGuard();
        guard.claim("a");
        assertFalse(guard.claim("b"));
    }

    @Test
    void theSameClientClaimingTwiceStillHoldsIt() {
        var guard = new SessionGuard();
        guard.claim("a");
        assertTrue(guard.claim("a"));
    }

    @Test
    void releasingLetsTheNextClientIn() {
        var guard = new SessionGuard();
        guard.claim("a");
        guard.release("a");
        assertTrue(guard.claim("b"));
    }

    @Test
    void aRefusedClientCannotReleaseTheHolder() {
        var guard = new SessionGuard();
        guard.claim("a");
        guard.claim("b");
        guard.release("b");
        assertFalse(guard.claim("c"));
    }
}
