package dm;

import java.util.concurrent.atomic.AtomicReference;

/**
 * One session, one connection — enforced rather than documented.
 *
 * <p>{@code WsHandler} captures a {@code WsContext} per turn and sends that turn's narration,
 * dice and diffs back to it. With two clients attached, a turn typed in one window is narrated
 * into the other, which presents as a Godot bug and is not one. The bridge period between the
 * two clients is exactly when that is most likely to happen, so it is five lines of Java rather
 * than a sentence someone has to remember.
 */
public final class SessionGuard {

    private final AtomicReference<String> holder = new AtomicReference<>();

    /** True if this session now owns the table. Idempotent for the holder. */
    public boolean claim(String sessionId) {
        return holder.compareAndSet(null, sessionId) || sessionId.equals(holder.get());
    }

    /** Only the holder can let go. A refused client closing must not free the table. */
    public void release(String sessionId) {
        holder.compareAndSet(sessionId, null);
    }
}
