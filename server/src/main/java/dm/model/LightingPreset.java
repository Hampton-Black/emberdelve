package dm.model;

/**
 * What a room's own fires are doing. BRAZIERLIT is TORCHLIT's air with no wall torches — the room
 * is lit only by fires standing on its floor, which are props. It is authored, never generated:
 * no kit lists it.
 */
public enum LightingPreset { TORCHLIT, BRAZIERLIT, DIM, DARK }
