import { create } from "zustand";
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

export const useGame = create<GameState>((set, get) => ({
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

  setConnected: (connected) => set({ connected }),
  setDemoMode: (demoMode) => set({ demoMode }),
  setScene: (scene) => set({ scene }),

  applyDiffs: (diffs) =>
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
        }
      }

      return { scene, mode };
    }),

  /**
   * Narration arrives a sentence at a time. Consecutive sentences from the same speaker
   * extend the current paragraph, so the transcript reads as prose rather than as a list
   * of fragments. A speaker change starts a new paragraph.
   *
   * Held behind the dice: see {@link throughGate}.
   */
  appendNarration: (segment) =>
    throughGate(() =>
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
    ),

  endNarration: () => throughGate(() => set({ awaitingDm: false })),

  sayAsPlayer: (text) =>
    set((state) => ({
      transcript: [...state.transcript, { kind: "prose", speakerId: "player", text }],
      awaitingDm: true,
    })),

  /**
   * Every roll reaches the log; only dramatic ones get thrown. Closing the narration gate here
   * is what makes "the dice decide, then the DM speaks" true by construction rather than by
   * luck — today the prose model is slow enough that the order is never in doubt, but a faster
   * one would otherwise announce the outcome over a die still in the air.
   */
  addRoll: (result) => {
    const dramatic = isDramatic(result, playerIds(get().scene));
    const startedAt = performance.now();

    if (dramatic) closeGateUntil(startedAt + revealAt(result.faces.length));

    set((state) => ({
      rolls: [...state.rolls, result],
      ...(dramatic ? { activeRoll: { result, startedAt }, diceDismissAt: null } : {}),
    }));

    // The log is a record, and records lag. Appending it now would print the total in the
    // sidebar while the die is still in the air, which spoils the throw.
    throughGate(() =>
      set((state) => ({ transcript: [...state.transcript, { kind: "roll", result }] })),
    );
  },

  // Errors bypass the gate: a stuck turn must never be hidden behind a die.
  setError: (error) => set({ error, awaitingDm: false }),
}));

/** Segments arrive pre-trimmed of nothing, so join with exactly one space. */
function joinProse(existing: string, addition: string): string {
  const left = existing.trimEnd();
  const right = addition.trimStart();
  if (!left) return right;
  if (!right) return left;
  return `${left} ${right}`;
}

function playerIds(scene: SceneState | null): ReadonlySet<string> {
  return new Set(scene?.entities.filter((e) => e.isPlayerControlled).map((e) => e.id) ?? []);
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
