import { useState } from "react";
import { GAME_SCENE_SKINS, getSceneSkinById } from "../../features/game/sceneSkins";
import type { GameSceneSkin } from "../../features/game/sceneSkins";

export type { GameSceneSkin };

export function useGameSceneSelection() {
  const [gameSceneId, setGameSceneId] = useState("");
  const gameScene = getSceneSkinById(gameSceneId); // null when id is "" → classic board
  return { gameSceneId, setGameSceneId, gameScene, gameScenes: GAME_SCENE_SKINS };
}
