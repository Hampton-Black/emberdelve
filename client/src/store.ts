import { create } from "zustand";
import { combatBegins, fell, initiativeSet } from "./audio/combat";
import { hold, mark, silence, silenceNow, speak } from "./audio/narration";
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
  //
  // The DM has the floor from this moment, not from the moment its first token lands. Those are
  // about 700ms apart, and in that window the debug bar was live and the input box was open —
  // long enough to start a fight underneath the opening narration, which the server then has to
  // drop as a collision. Every other way of giving the DM the floor sets this on the way in;
  // this one was the exception.
  setStarted: () => set({ started: true, awaitingDm: true }),
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
   * Queued with the narration, in arrival order, and released when the voice reaches it.
   *
   * <p>A hit point bar that empties while the attack die is still in the air has answered the
   * question the die was asking — and one that empties twenty seconds before the narrator says
   * the blow was struck is worse still. Both are the same bug: the world moving on a different
   * clock from the voice describing it.
   *
   * <p>When nothing is queued this runs on the spot, which is every move and every reveal the
   * player makes for themselves — so click-to-move keeps its 100ms budget. It waits only when
   * the DM is mid-sentence, and then it should.
   */
  applyDiffs: (diffs) =>
    mark(() =>
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
   * Paced by the voice like everything else: see the queue in `audio/narration.ts`.
   */
  // The transcript update is the queue's callback rather than something that happens now: the
  // voice paces the text, so the player reads at the speed the DM is talking instead of racing
  // twenty seconds ahead of it.
  /**
   * Narration arrives a sentence at a time. Consecutive sentences from the same speaker extend
   * the current paragraph, so the transcript reads as prose rather than as a list of fragments.
   * A speaker change starts a new paragraph.
   *
   * <p>The transcript update is the queue's callback rather than something that happens now: the
   * voice paces the text, so the player reads at the speed the DM is talking instead of racing
   * twenty seconds ahead of it.
   */
  appendNarration: (segment) =>
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
    ),

  // Behind the queue: clearing this early would end the thinking indicator while lines were
  // still appearing, and break the paragraph merging in appendNarration.
  endNarration: () => mark(() => set({ awaitingDm: false })),

  sayAsPlayer: (text) => {
    // A new turn drops the rest of the last one, but lets the sentence in the air finish.
    silence();
    set((state) => ({
      transcript: [...state.transcript, { kind: "prose", speakerId: "player", text }],
      awaitingDm: true,
    }));
  },

  /**
   * Every roll reaches the log; only dramatic ones get thrown.
   *
   * <p>Queued like everything else, so "the dice decide, then the DM speaks" is true by
   * construction rather than by luck. A dramatic roll holds the queue for as long as it is in
   * the air, which is what stops narration that commits to a result from arriving ahead of the
   * die showing it.
   */
  addRoll: (result) => {
    const dramatic = isDramatic(result);

    // Holding the whole queue rather than the swing alone is what keeps the order true: the hit
    // point bar, the damage line and the blow are all consequences of this roll, and none of
    // them may arrive before the player has read what the roll said.
    const beat = result.request.purpose === "ATTACK" ? IMPACT_BEAT_MS : 0;
    const airtime = dramatic ? revealAt(result.faces.length) + beat : 0;

    const throwIt = () =>
      set((state) => ({
        rolls: [...state.rolls, result],
        ...(dramatic
          ? { activeRoll: { result, startedAt: performance.now() }, diceDismissAt: null }
          : {}),
      }));

    // The log is a record, and records lag. Appending it as the die is thrown would print the
    // total in the sidebar while it was still in the air, which spoils the throw.
    const settle = () =>
      set((state) => ({
        transcript: [...state.transcript, { kind: "roll", result }],
        // Released here rather than on arrival so the swing plays when the die answers, not when
        // the server decided.
        ...(result.request.purpose === "ATTACK" && result.request.targetId
          ? {
              strike: {
                actorId: result.request.actorId,
                targetId: result.request.targetId,
                connected: result.outcome === "HIT" || result.outcome === "CRIT",
                at: performance.now(),
              },
              // The blow is the tray's cue to leave, exactly as narration is out of combat. The
              // readout dissolves as the sword comes down, which moves the eye from the tray
              // back to the board. COMBAT_HOLD_MS is only the backstop for rolls with no swing
              // behind them.
              ...(state.diceDismissAt === null ? { diceDismissAt: performance.now() } : {}),
            }
          : {}),
      }));

    if (dramatic) {
      hold(airtime, throwIt);
      mark(settle);
    } else {
      mark(() => {
        throwIt();
        settle();
      });
    }
  },

  // Errors bypass the queue: a stuck turn must never be hidden behind a die or a sentence.
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
    holdFloor(CEREMONY_MS);
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

/**
 * Take the floor for a while with nothing to say.
 *
 * <p>The combat ceremony is the only thing that needs this: it is a beat made of drums, a camera
 * move and a bar assembling, none of which the queue would otherwise know to wait for.
 */
function holdFloor(ms: number): void {
  hold(ms, () => {});
}
