import { create } from "zustand";
import type {
  Diff,
  Mode,
  NarrationSegment,
  RollResult,
  SceneState,
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
  transcript: NarrationSegment[];
  rolls: RollResult[];
  error: string | null;

  setConnected: (connected: boolean) => void;
  setDemoMode: (demoMode: boolean) => void;
  setScene: (scene: SceneState) => void;
  applyDiffs: (diffs: Diff[]) => void;
  appendNarration: (segment: NarrationSegment, final: boolean) => void;
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
   * Narration streams. A non-final chunk from the same speaker extends the last entry
   * rather than appending a new one, so the transcript reads as prose and not as tokens.
   */
  appendNarration: (segment, final) =>
    set((state) => {
      const last = state.transcript.at(-1);

      if (last !== undefined && last.speakerId === segment.speakerId && !final) {
        const transcript = state.transcript.slice(0, -1);
        transcript.push({ ...last, text: last.text + segment.text });
        return { transcript };
      }
      return { transcript: [...state.transcript, segment] };
    }),

  addRoll: (result) => set((state) => ({ rolls: [...state.rolls, result] })),
  setError: (error) => set({ error }),
}));
