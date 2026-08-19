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
}

// ---- Diffs: the tagged union that arrives after the initial scene ----

export type Diff =
  | { kind: "EntityAdded"; entity: EntityView }
  | { kind: "EntityRemoved"; entityId: string }
  | { kind: "EntityMoved"; entityId: string; fromX: number; fromY: number; x: number; y: number }
  | { kind: "StatChanged"; entityId: string; stat: string; from: number; to: number }
  | { kind: "ModeChanged"; mode: Mode }
  | { kind: "PropRevealed"; prop: Prop };

// ---- Dice ----

export type Advantage = "NORMAL" | "ADVANTAGE" | "DISADVANTAGE";
export type RollPurpose = "ATTACK" | "SAVE" | "SKILL_CHECK" | "DAMAGE" | "INITIATIVE";
export type Outcome = "CRIT" | "HIT" | "MISS" | "SUCCESS" | "FAILURE" | "CRIT_FAIL";

export interface RollRequest {
  dice: string;
  modifier: number;
  advantage: Advantage;
  purpose: RollPurpose;
  actorId: string;
  targetId?: string;
  dc?: number;
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

// ---- Wire envelopes ----

export type ServerMessage =
  | { type: "hello"; demoMode: boolean }
  | { type: "scene"; scene: SceneState }
  | { type: "diffs"; diffs: Diff[] }
  | { type: "narration"; segment: NarrationSegment; final: boolean }
  | { type: "roll"; result: RollResult }
  | { type: "error"; message: string };

export type ClientMessage =
  | { type: "freeText"; actorId: string; text: string }
  | { type: "moveTo"; actorId: string; x: number; y: number }
  | { type: "attack"; actorId: string; targetId: string }
  | { type: "endTurn"; actorId: string };
