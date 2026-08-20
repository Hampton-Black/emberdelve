import { create } from "zustand";
import { combatBegins, fell, initiativeSet } from "./audio/combat";
import { mark, silence, silenceNow, speak } from "./audio/narration";
import { footsteps, lidOpens, revealed } from "./audio/world";
import { CEREMONY_MS } from "./combat/opening";
import { IMPACT_BEAT_MS, isDramatic, revealAt } from "./dice/tumble";
import type {
  CombatView,
  Diff,
  Mode,
  NarrationSegment,
  RollResult,
  SceneState,
  TranscriptEntry,
} from "./types";

/**
 * The combat HUD and where it is in its arrival.
 *
 * <p>Separate from `scene.combat` because the chrome outlives the fight by design: the bar has to
 * still have names and initiative totals to draw while it is dissolving, and by then the server
 * has already told us the fight is over. `openedAt` is what the animation times against, and it
 * doubles as the identity of the fight — a second one must replay the ceremony rather than
 * inherit the first one's finished state.
 */
export interface CombatBeat {
  view: CombatView;
  openedAt: number;
  /** When the fight ended, or null while it is still running. */
  closingAt: number | null;
}

/**
 * ALL game state lives here (invariant #3). React and Three.js both subscribe.
 * Nothing game-related may live in React component state — doing so causes canvas
 * re-creation bugs that are very hard to diagnose.
 */
interface GameState {
  connected: boolean;
  demoMode: boolean;
  scene: SceneState | null;
  mode: Mode;
  transcript: TranscriptEntry[];
  rolls: RollResult[];
  error: string | null;
  /** True while the DM is mid-turn. Drives the thinking indicator and input lockout. */
  awaitingDm: boolean;

  /**
   * Whether the player has started the session.
   *
   * <p>In the store rather than in React because three things read it — the title, the input box
   * and the canvas — and because it gates a real event: a socket opening is not a player
   * arriving, and until someone has clicked, the browser will not make a sound.
   */
  started: boolean;

  /** The roll the tray is currently throwing, and when it arrived. Null when nothing is in flight. */
  activeRoll: { result: RollResult; startedAt: number } | null;
  /** When the tray began fading, in `performance.now()` terms. Null while it is still held. */
  diceDismissAt: number | null;

  /**
   * The fight's chrome, or the last fight's while it fades. Null until the first one starts.
   */
  combatBeat: CombatBeat | null;

  /**
   * The most recent attack, published once its dice have landed. The renderer watches this and
   * plays the swing; it is a notification rather than state, which is why it carries `at` — two
   * identical misses in a row are two separate events and must not collapse into one.
   */
  strike: { actorId: string; targetId: string; connected: boolean; at: number } | null;

  setConnected: (connected: boolean) => void;
  setStarted: () => void;
  setDemoMode: (demoMode: boolean) => void;
  setScene: (scene: SceneState) => void;
  applyDiffs: (diffs: Diff[]) => void;
  appendNarration: (segment: NarrationSegment) => void;
  endNarration: () => void;
  sayAsPlayer: (text: string) => void;
  addRoll: (result: RollResult) => void;
  setError: (error: string | null) => void;
}

export const useGame = create<GameState>((set) => ({
  connected: false,
  demoMode: false,
  scene: null,
  mode: "EXPLORATION",
  transcript: [],
  rolls: [],
  error: null,
  awaitingDm: false,
  started: false,
  activeRoll: null,
  diceDismissAt: null,
  strike: null,
  combatBeat: null,

  setConnected: (connected) => set({ connected }),
  // One way. A dropped socket reconnects to a session already under way; it does not put the
  // title back up, and the server will not narrate the opening twice.
  setStarted: () => set({ started: true }),
  setDemoMode: (demoMode) => set({ demoMode }),
  // Mode rides with the scene rather than being left to the diff that changed it: a client
  // that connects mid-fight gets one message, and it has to be the whole truth.
  setScene: (scene) =>
    set({
      scene,
      mode: scene.mode,
      // Backdated past the ceremony on purpose: a socket that drops and reconnects during a
      // fight rejoins one already in progress, and replaying the drums and the initiative
      // ceremony for it would announce something that happened minutes ago.
      combatBeat: scene.combat
        ? { view: scene.combat, openedAt: performance.now() - CEREMONY_MS, closingAt: null }
        : null,
    }),

  /**
   * Held behind the dice for the same reason narration is: a hit point bar that empties while
   * the attack die is still in the air has answered the question the die was asking. When no
   * roll is in flight — every move, every reveal — the gate is open and this runs on the spot,
   * which is what keeps click-to-move inside its 100ms budget.
   */
  applyDiffs: (diffs) =>
    throughGate(() =>
      set((state) => {
        if (!state.scene) return state;

        let scene = state.scene;
        let mode = state.mode;
        // Which side of a mode flip this batch crossed, decided in the loop and acted on after
        // it: the ceremony needs the CombatView, and that arrives in the CombatChanged diff
        // sitting behind the ModeChanged one.
        let opened = false;
        let closed = false;

        for (const diff of diffs) {
          switch (diff.kind) {
            case "EntityAdded": {
              // Only when it is genuinely new. This diff is idempotent by id, and a replaced
              // goblin is a debug respawn rather than a lid coming off a second time.
              const already = scene.entities.some((e) => e.id === diff.entity.id);
              if (!already && !diff.entity.isPlayerControlled) lidOpens();

              // Idempotent by id. M0's goblin has a hardcoded id, so spawning a second one
              // replaces the first server-side — appending here would leave a phantom behind.
              scene = {
                ...scene,
                entities: already
                  ? scene.entities.map((e) => (e.id === diff.entity.id ? diff.entity : e))
                  : [...scene.entities, diff.entity],
              };
              break;
            }

            case "EntityRemoved":
              scene = {
                ...scene,
                entities: scene.entities.filter((e) => e.id !== diff.entityId),
              };
              break;

            case "EntityMoved":
              footsteps(Math.max(Math.abs(diff.x - diff.fromX), Math.abs(diff.y - diff.fromY)));
              scene = {
                ...scene,
                entities: scene.entities.map((e) =>
                  e.id === diff.entityId ? { ...e, x: diff.x, y: diff.y } : e,
                ),
              };
              break;

            case "StatChanged":
              // The blow that caused this is still mid-swing; `fell` waits for it to land.
              if (diff.stat === "hp" && diff.to <= 0 && diff.from > 0) fell();
              scene = {
                ...scene,
                entities: scene.entities.map((e) =>
                  e.id === diff.entityId && diff.stat === "hp"
                    ? { ...e, hp: diff.to }
                    : e,
                ),
              };
              break;

            case "ModeChanged":
              if (mode !== diff.mode) {
                // Initiative is rolled as a batch and never reaches the tray, so the sting is
                // the only thing announcing the fight until the bar arrives behind it.
                if (diff.mode === "COMBAT") combatBegins();
                opened = diff.mode === "COMBAT";
                closed = diff.mode === "EXPLORATION";
              }
              mode = diff.mode;
              break;

            case "PropRevealed":
              if (!scene.props.some((p) => p.id === diff.prop.id)) revealed();
              scene = {
                ...scene,
                props: scene.props.some((p) => p.id === diff.prop.id)
                  ? scene.props.map((p) => (p.id === diff.prop.id ? diff.prop : p))
                  : [...scene.props, diff.prop],
              };
              break;

            case "CombatChanged":
              // Replaced wholesale, never merged. The server sends the entire legal picture each
              // time precisely so the client has no chance to hold a half-updated one.
              scene = { ...scene, combat: diff.combat };
              break;
          }
        }

        return { scene, mode, combatBeat: beatFor(state.combatBeat, scene.combat, opened, closed) };
      }),
    ),

  /**
   * Narration arrives a sentence at a time. Consecutive sentences from the same speaker
   * extend the current paragraph, so the transcript reads as prose rather than as a list
   * of fragments. A speaker change starts a new paragraph.
   *
   * Held behind the dice: see {@link throughGate}.
   */
  appendNarration: (segment) =>
    throughGate(() => {
      // Queued from inside the gate, so the DM never announces an outcome over a die still in
      // the air. The queue itself knows nothing about dice — it inherits the ordering.
      //
      // The transcript update is the queue's callback rather than something that happens now:
      // the voice paces the text, so the player reads at the speed the DM is talking instead of
      // racing twenty seconds ahead of it.
      speak(segment, () =>
        set((state) => {
          // The first word of narration is the tray's cue to leave.
          const dismiss =
            state.activeRoll && state.diceDismissAt === null
              ? { diceDismissAt: performance.now() }
              : {};

          const last = state.transcript.at(-1);
          if (state.awaitingDm && last?.kind === "prose" && last.speakerId === segment.speakerId) {
            const transcript = state.transcript.slice(0, -1);
            transcript.push({ ...last, text: joinProse(last.text, segment.text) });
            return { transcript, ...dismiss };
          }

          return {
            transcript: [...state.transcript, { kind: "prose", ...segment }],
            awaitingDm: true,
            ...dismiss,
          };
        }),
      );
    }),

  // Behind the queue, not just the gate: clearing this early would end the thinking indicator
  // while lines were still appearing, and break the paragraph merging in appendNarration.
  endNarration: () => throughGate(() => mark(() => set({ awaitingDm: false }))),

  sayAsPlayer: (text) => {
    // A new turn drops the rest of the last one, but lets the sentence in the air finish.
    silence();
    set((state) => ({
      transcript: [...state.transcript, { kind: "prose", speakerId: "player", text }],
      awaitingDm: true,
    }));
  },

  /**
   * Every roll reaches the log; only dramatic ones get thrown. Closing the narration gate here
   * is what makes "the dice decide, then the DM speaks" true by construction rather than by
   * luck — today the prose model is slow enough that the order is never in doubt, but a faster
   * one would otherwise announce the outcome over a die still in the air.
   */
  addRoll: (result) => {
    const dramatic = isDramatic(result);
    const startedAt = performance.now();

    // Delaying the gate rather than the swing alone is what keeps the order true: the hit point
    // bar, the damage line and the blow are all consequences of this roll, and none of them may
    // arrive before the player has read what the roll said.
    const beat = result.request.purpose === "ATTACK" ? IMPACT_BEAT_MS : 0;
    if (dramatic) closeGateUntil(startedAt + revealAt(result.faces.length) + beat);

    set((state) => ({
      rolls: [...state.rolls, result],
      ...(dramatic ? { activeRoll: { result, startedAt }, diceDismissAt: null } : {}),
    }));

    // The log is a record, and records lag. Appending it now would print the total in the
    // sidebar while the die is still in the air, which spoils the throw.
    throughGate(() =>
      set((state) => ({
        transcript: [...state.transcript, { kind: "roll", result }],
        // Released here rather than on arrival so the swing plays when the die answers, not
        // when the server decided. Same gate, so it cannot get ahead of the damage it caused.
        ...(result.request.purpose === "ATTACK" && result.request.targetId
          ? {
              strike: {
                actorId: result.request.actorId,
                targetId: result.request.targetId,
                connected: result.outcome === "HIT" || result.outcome === "CRIT",
                at: performance.now(),
              },
              // The blow is the tray's cue to leave, exactly as narration is out of combat. The
              // readout dissolves as the sword comes down, which is what moves the eye from the
              // tray back to the board. COMBAT_HOLD_MS is only the backstop for rolls with no
              // swing behind them.
              ...(state.diceDismissAt === null ? { diceDismissAt: performance.now() } : {}),
            }
          : {}),
      })),
    );
  },

  // Errors bypass the gate: a stuck turn must never be hidden behind a die.
  setError: (error) => {
    // An error is the one case worth cutting mid-word for.
    silenceNow();
    set({ error, awaitingDm: false });
  },
}));

/**
 * The combat chrome after a batch of diffs.
 *
 * <p>Not pure, and deliberately so — this is where the fight's opening beat is scheduled, and it
 * is the first point at which both facts it needs are known: that the mode flipped, and who is
 * in the initiative order. Splitting the schedule away from the state it times against is how
 * the two drift apart.
 */
function beatFor(
  current: CombatBeat | null,
  combat: CombatView | null,
  opened: boolean,
  closed: boolean,
): CombatBeat | null {
  const now = performance.now();

  if (opened && combat) {
    // Everything downstream waits behind the ceremony: the DM's first line about the fight, the
    // goblin's opening move, and every consequence of it. The same gate the dice use, for the
    // same reason — a beat the narrator talks over is not a beat.
    closeGateUntil(now + CEREMONY_MS);
    initiativeSet(combat.order.length);
    return { view: combat, openedAt: now, closingAt: null };
  }

  if (closed) {
    // The retained view is the last live one. `combat` is already null by the time this runs,
    // and the bar has to keep drawing names and totals all the way through its dissolve.
    return current && current.closingAt === null ? { ...current, closingAt: now } : current;
  }

  if (!combat) return current;
  // An ordinary update — the turn passing, movement spent, someone going down. The backdated
  // fallback covers a CombatChanged arriving without an opening, which is what a mid-fight
  // reconnect looks like if the scene has not landed yet.
  return current
    ? { ...current, view: combat }
    : { view: combat, openedAt: now - CEREMONY_MS, closingAt: null };
}

/** Segments arrive pre-trimmed of nothing, so join with exactly one space. */
function joinProse(existing: string, addition: string): string {
  const left = existing.trimEnd();
  const right = addition.trimStart();
  if (!left) return right;
  if (!right) return left;
  return `${left} ${right}`;
}

// ---- The narration gate ----
//
// Narration is withheld until the dice it describes have landed, and released in arrival order.
// Deliberately timer-based rather than driven by the tray component: if the tray never mounts,
// the gate must still open.

let gateOpensAt = 0;
let held: Array<() => void> = [];
let timer: ReturnType<typeof setTimeout> | null = null;

function throughGate(action: () => void): void {
  if (gateOpensAt - performance.now() <= 0 && held.length === 0) {
    action();
    return;
  }
  held.push(action);
  if (timer === null) {
    timer = setTimeout(openGate, Math.max(gateOpensAt - performance.now(), 0));
  }
}

function openGate(): void {
  timer = null;

  // A second roll may have extended the hold while narration was queued.
  const remaining = gateOpensAt - performance.now();
  if (remaining > 0 && held.length > 0) {
    timer = setTimeout(openGate, remaining);
    return;
  }

  const queued = held;
  held = [];
  gateOpensAt = 0;

  for (let i = 0; i < queued.length; i++) {
    queued[i]();

    // An action may have closed the gate behind itself — the mode flipping to COMBAT does
    // exactly that, to buy the initiative ceremony its stage. Anything still queued belongs
    // behind the new hold rather than in front of it, and running it here would let the
    // goblin's first move land during the drums.
    const reclosed = gateOpensAt - performance.now();
    if (reclosed > 0) {
      held = queued.slice(i + 1).concat(held);
      timer = setTimeout(openGate, reclosed);
      return;
    }
  }
}

function closeGateUntil(at: number): void {
  gateOpensAt = Math.max(gateOpensAt, at);
}
