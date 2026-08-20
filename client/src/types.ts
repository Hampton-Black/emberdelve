// Hand-written mirror of the Java records in server/src/main/java/dm/model.
// M0 only — codegen from one IDL arrives in M1. If you change a record over there,
// change it here in the same commit.

export type FloorType = "STONE" | "CRACKED_STONE" | "TILED";
export type WallType = "STONE" | "CARVED";
export type LightingPreset = "TORCHLIT" | "DIM" | "DARK";
export type Mode = "EXPLORATION" | "COMBAT";

export type PropType =
  | "SARCOPHAGUS"
  | "BRAZIER"
  | "PILLAR"
  | "RUBBLE"
  | "ALCOVE"
  | "DOOR";

export interface Prop {
  id: string;
  type: PropType;
  x: number;
  y: number;
  rotation: number;
  hidden: boolean;
}

export interface EntityView {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  hp: number;
  maxHp: number;
  isPlayerControlled: boolean;
}

export interface SceneState {
  roomId: string;
  width: number;
  height: number;
  floorType: FloorType;
  wallType: WallType;
  props: Prop[];
  entities: EntityView[];
  lighting: LightingPreset;
  mode: Mode;
  /** The fight in progress, or null. Rides along so a reconnect lands mid-combat intact. */
  combat: CombatView | null;
}

// ---- Combat ----

export interface Square {
  x: number;
  y: number;
}

export interface Combatant {
  entityId: string;
  name: string;
  initiative: number;
  isPlayerControlled: boolean;
}

/**
 * The server's answer to "what may this combatant do right now", not the inputs to work it out.
 *
 * <p>`legalMoves` and `legalTargets` arrive finished. Nothing on the client knows about speed,
 * reach or blocking props — it highlights what it is handed (invariant #1). When those rules
 * grow, no code in here changes.
 */
export interface CombatView {
  order: Combatant[];
  activeId: string;
  round: number;
  movementRemaining: number;
  actionAvailable: boolean;
  legalMoves: Square[];
  legalTargets: string[];
}

// ---- Diffs: the tagged union that arrives after the initial scene ----

export type Diff =
  | { kind: "EntityAdded"; entity: EntityView }
  | { kind: "EntityRemoved"; entityId: string }
  | { kind: "EntityMoved"; entityId: string; fromX: number; fromY: number; x: number; y: number }
  | { kind: "StatChanged"; entityId: string; stat: string; from: number; to: number }
  | { kind: "ModeChanged"; mode: Mode }
  | { kind: "PropRevealed"; prop: Prop }
  | { kind: "CombatChanged"; combat: CombatView | null };

// ---- Dice ----

export type Advantage = "NORMAL" | "ADVANTAGE" | "DISADVANTAGE";
export type Skill = "ATHLETICS" | "PERCEPTION" | "INVESTIGATION" | "STEALTH" | "PERSUASION";
export type RollPurpose = "ATTACK" | "SAVE" | "SKILL_CHECK" | "DAMAGE" | "INITIATIVE";
export type Outcome = "CRIT" | "HIT" | "MISS" | "SUCCESS" | "FAILURE" | "CRIT_FAIL";

export interface RollRequest {
  dice: string;
  modifier: number;
  advantage: Advantage;
  purpose: RollPurpose;
  actorId: string;
  /**
   * Null, not absent. These are `Optional` on the server and Jackson writes an empty one as
   * `null`, so `x === undefined` silently misses every one of them — which is how an initiative
   * roll ends up captioned "DC null".
   */
  targetId: string | null;
  dc: number | null;
  /** Present on skill checks, so the log and the tray can name what was tested. */
  skill: Skill | null;
}

export interface RollResult {
  request: RollRequest;
  /** Individual dice, never collapsed to a total (invariant #5). */
  faces: number[];
  total: number;
  outcome: Outcome;
}

// ---- Narration ----

export interface NarrationSegment {
  speakerId: string;
  text: string;
}

/**
 * One entry in the transcript. Rolls live in the same list as prose so the log preserves the
 * order things actually happened in — the dice, then the narration that commits to them.
 * `player` is a local speaker, never sent by the server.
 */
export type TranscriptEntry =
  | { kind: "prose"; speakerId: string; text: string }
  | { kind: "roll"; result: RollResult };

// ---- Wire envelopes ----

export type ServerMessage =
  | { type: "hello"; demoMode: boolean; voice: boolean }
  | { type: "scene"; scene: SceneState }
  | { type: "diffs"; diffs: Diff[] }
  | { type: "narration"; segment: NarrationSegment }
  | { type: "narrationEnd" }
  | { type: "roll"; result: RollResult }
  | { type: "error"; message: string };

export type ClientMessage =
  /** "A player is here and has clicked something." Cues the opening narration. */
  | { type: "begin" }
  | { type: "restart" }
  | { type: "freeText"; actorId: string; text: string }
  | { type: "moveTo"; actorId: string; x: number; y: number }
  | { type: "attack"; actorId: string; targetId: string }
  | { type: "endTurn"; actorId: string };
