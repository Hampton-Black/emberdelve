import { useServerVoice } from "./audio/narration";
import { useGame } from "./store";
import type { ClientMessage, ServerMessage } from "./types";

const URL = "ws://localhost:7070/ws";
const RECONNECT_DELAY_MS = 1000;

let socket: WebSocket | null = null;

/**
 * Set while a restart is in flight, so the fresh scene the server sends back is recognised as
 * the end of this session rather than the middle of one.
 */
let restarting = false;

/**
 * One connection for the whole app. The server is authoritative (invariant #1) — everything
 * arriving here is applied as told, never recomputed.
 */
export function connect(): void {
  if (socket && socket.readyState <= WebSocket.OPEN) return;

  socket = new WebSocket(URL);

  socket.onopen = () => {
    useGame.getState().setConnected(true);
    useGame.getState().setError(null);
  };

  socket.onmessage = (event) => {
    let message: ServerMessage;
    try {
      message = JSON.parse(event.data as string) as ServerMessage;
    } catch {
      console.error("unparseable server message:", event.data);
      return;
    }
    dispatch(message);
  };

  socket.onclose = () => {
    useGame.getState().setConnected(false);
    // M0 has no session recovery — reconnecting just re-attaches to a running server.
    setTimeout(connect, RECONNECT_DELAY_MS);
  };

  socket.onerror = () => {
    useGame.getState().setError("Lost connection to the server.");
  };
}

function dispatch(message: ServerMessage): void {
  const game = useGame.getState();

  switch (message.type) {
    case "hello":
      game.setDemoMode(message.demoMode);
      // Before any narration can arrive, which is the only ordering that matters here.
      useServerVoice(message.voice);
      break;
    case "scene":
      // A restart reaches back further than the store does — the transcript, the dice log, the
      // renderer's tokens and the title screen are all still the old game's. Reloading is the
      // one move that clears every one of them, and it costs nothing: the session it would be
      // preserving has just been thrown away on purpose.
      if (restarting) {
        window.location.reload();
        return;
      }
      game.setScene(message.scene);
      break;
    case "diffs":
      game.applyDiffs(message.diffs);
      break;
    case "narration":
      game.appendNarration(message.segment);
      break;
    case "narrationEnd":
      game.endNarration();
      break;
    case "roll":
      game.addRoll(message.result);
      break;
    case "error":
      game.setError(message.message);
      break;
  }
}

export function send(message: ClientMessage): void {
  if (socket?.readyState !== WebSocket.OPEN) {
    console.warn("dropped message, socket not open:", message);
    return;
  }
  socket.send(JSON.stringify(message));
}

/**
 * Start a new session.
 *
 * <p>Deliberately not a page reload on its own: the reload has to happen <em>after</em> the
 * server has thrown the old game away, or the new page reconnects to the old one.
 */
export function restart(): void {
  restarting = true;
  send({ type: "restart" });
}
