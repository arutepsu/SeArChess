export interface ClipSpec {
  frameCount: number;
  frameSize: {
    width: number;
    height: number;
  };
}

export interface SpriteSheetSpec {
  path: string;
  clipSpec: string;
}

export type PlaybackMode = "Clamp" | "Loop";

export interface StatePlaybackEntry {
  state: "Idle" | "Move" | "Attack" | "Hit" | "Dead";
  mode: PlaybackMode;
  segments: string[];
}

export interface SpriteCatalog {
  theme: string;
  clipSpecs: Record<string, ClipSpec>;
  spriteSheets: Record<string, SpriteSheetSpec>;
  statePlayback: Record<string, StatePlaybackEntry>;
}

let cachedCatalog: SpriteCatalog | null = null;

export async function loadSpriteCatalog(): Promise<SpriteCatalog> {
  if (cachedCatalog) {
    return cachedCatalog;
  }

  const response = await fetch("/assets/sprite_catalog.json");
  if (!response.ok) {
    throw new Error(`Failed to load sprite catalog: ${response.status}`);
  }

  cachedCatalog = (await response.json()) as SpriteCatalog;
  return cachedCatalog;
}
