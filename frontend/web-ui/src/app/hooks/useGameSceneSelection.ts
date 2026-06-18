import { useState } from "react";
import { GAME_SCENE_SKINS, getSceneSkinById } from "../../features/game/sceneSkins";
import type { GameSceneSkin } from "../../features/game/sceneSkins";

export type { GameSceneSkin };

const STORAGE_KEY = "searchess.gameScene";
const DEFAULT_SCENE_ID = "oni";

/** Valid stored ids: every skin id plus "" (Classic). */
const VALID_IDS = new Set([...GAME_SCENE_SKINS.map((s) => s.id), ""]);

function readStoredId(): string {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw !== null && VALID_IDS.has(raw)) return raw;
  } catch {
    // localStorage unavailable (SSR / private-browsing restrictions)
  }
  return DEFAULT_SCENE_ID;
}

function writeStoredId(id: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, id);
  } catch {
    // ignore write failures
  }
}

export function useGameSceneSelection() {
  const [gameSceneId, setGameSceneIdState] = useState<string>(readStoredId);

  const setGameSceneId = (id: string) => {
    const safe = VALID_IDS.has(id) ? id : DEFAULT_SCENE_ID;
    writeStoredId(safe);
    setGameSceneIdState(safe);
  };

  const gameScene = getSceneSkinById(gameSceneId); // null when id is "" → Classic board
  return { gameSceneId, setGameSceneId, gameScene, gameScenes: GAME_SCENE_SKINS };
}
