import { create } from "zustand";
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
            scene = { ...scene, entities: [...scene.entities, diff.entity] };
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
   */
  appendNarration: (segment) =>
    set((state) => {
      const last = state.transcript.at(-1);

      if (state.awaitingDm && last?.speakerId === segment.speakerId) {
        const transcript = state.transcript.slice(0, -1);
        transcript.push({ ...last, text: joinProse(last.text, segment.text) });
        return { transcript };
      }
      return { transcript: [...state.transcript, { ...segment }], awaitingDm: true };
    }),

  endNarration: () => set({ awaitingDm: false }),

  sayAsPlayer: (text) =>
    set((state) => ({
      transcript: [...state.transcript, { speakerId: "player", text }],
      awaitingDm: true,
    })),

  addRoll: (result) => set((state) => ({ rolls: [...state.rolls, result] })),
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
