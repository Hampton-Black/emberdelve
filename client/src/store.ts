import { create } from "zustand";
import { mark, silence, silenceNow, speak } from "./audio/narration";
import { isDramatic, revealAt } from "./dice/tumble";
import type {
  Diff,
  Mode,
  NarrationSegment,
  RollResult,
  SceneState,
  TranscriptEntry,
} from "./types";

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

  /** The roll the tray is currently throwing, and when it arrived. Null when nothing is in flight. */
  activeRoll: { result: RollResult; startedAt: number } | null;
  /** When the tray began fading, in `performance.now()` terms. Null while it is still held. */
  diceDismissAt: number | null;

  /**
   * The most recent attack, published once its dice have landed. The renderer watches this and
   * plays the swing; it is a notification rather than state, which is why it carries `at` — two
   * identical misses in a row are two separate events and must not collapse into one.
   */
  strike: { actorId: string; targetId: string; at: number } | null;

  setConnected: (connected: boolean) => void;
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
  activeRoll: null,
  diceDismissAt: null,
  strike: null,

  setConnected: (connected) => set({ connected }),
  setDemoMode: (demoMode) => set({ demoMode }),
  // Mode rides with the scene rather than being left to the diff that changed it: a client
  // that connects mid-fight gets one message, and it has to be the whole truth.
  setScene: (scene) => set({ scene, mode: scene.mode }),

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

        for (const diff of diffs) {
          switch (diff.kind) {
            case "EntityAdded":
              // Idempotent by id. M0's goblin has a hardcoded id, so spawning a second one
              // replaces the first server-side — appending here would leave a phantom behind.
              scene = {
                ...scene,
                entities: scene.entities.some((e) => e.id === diff.entity.id)
                  ? scene.entities.map((e) => (e.id === diff.entity.id ? diff.entity : e))
                  : [...scene.entities, diff.entity],
              };
              break;

            case "EntityRemoved":
              scene = {
                ...scene,
                entities: scene.entities.filter((e) => e.id !== diff.entityId),
              };
              break;

            case "EntityMoved":
              scene = {
                ...scene,
                entities: scene.entities.map((e) =>
                  e.id === diff.entityId ? { ...e, x: diff.x, y: diff.y } : e,
                ),
              };
              break;

            case "StatChanged":
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
              mode = diff.mode;
              break;

            case "PropRevealed":
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

        return { scene, mode };
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

    if (dramatic) closeGateUntil(startedAt + revealAt(result.faces.length));

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
                at: performance.now(),
              },
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
  for (const action of queued) action();
}

function closeGateUntil(at: number): void {
  gateOpensAt = Math.max(gateOpensAt, at);
}
