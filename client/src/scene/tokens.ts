import * as THREE from "three";
import type { EntityView } from "../types";

/**
 * Entity tokens.
 *
 * <p>Placeholder geometry until Kenney's Mini Characters land in
 * {@code /assets/kits/characters}. Swapping in a rigged glTF means replacing {@link buildToken}
 * and nothing else — position, HP and selection all key off the group, not the mesh.
 */

const PALETTE: Record<string, { body: number; trim: number }> = {
  fighter: { body: 0x5c7fa3, trim: 0xd6c9a8 },
  goblin: { body: 0x6f8f4a, trim: 0x8f4a3d },
};

const DEFAULT = { body: 0x8a8a8a, trim: 0xcccccc };

export function buildToken(entity: EntityView): THREE.Group {
  const group = new THREE.Group();
  const colors = PALETTE[entity.kind] ?? DEFAULT;
  const scale = entity.kind === "goblin" ? 0.78 : 1;

  const base = new THREE.Mesh(
    new THREE.CylinderGeometry(0.38, 0.4, 0.06, 16),
    new THREE.MeshStandardMaterial({ color: colors.trim, roughness: 0.7, metalness: 0.2 }),
  );
  base.position.y = 0.03;

  const body = new THREE.Mesh(
    new THREE.CapsuleGeometry(0.22 * scale, 0.5 * scale, 4, 12),
    new THREE.MeshStandardMaterial({ color: colors.body, roughness: 0.75 }),
  );
  body.position.y = (0.42 + 0.25) * scale;

  const head = new THREE.Mesh(
    new THREE.SphereGeometry(0.16 * scale, 12, 10),
    new THREE.MeshStandardMaterial({ color: colors.trim, roughness: 0.8 }),
  );
  head.position.y = (0.95 + 0.05) * scale;

  group.add(base, body, head);
  group.name = `entity:${entity.id}`;
  group.traverse((child) => {
    if ((child as THREE.Mesh).isMesh) {
      child.castShadow = true;
      child.receiveShadow = true;
    }
  });

  return group;
}
