import board1Url from "../../assets/boards/board1.jpg";
import board2Url from "../../assets/boards/board2.jpg";

export interface GameSceneSkin {
  id: string;
  label: string;
  imageUrl: string;
  sceneSize: number;
  /**
   * Position of the playable 8×8 board grid inside the full scene image.
   * All values are fractions of `sceneSize` (0..1).
   *
   * Calibration guide — tweak if pieces drift:
   *   left/top : increase to shift the grid right / down
   *   width/height : increase to make the grid larger inside the scene
   */
  boardRect: {
    left: number;
    top: number;
    width: number;
    height: number;
  };
}

export const GAME_SCENE_SKINS: GameSceneSkin[] = [
  {
    id: "sakura",
    label: "Sakura",
    imageUrl: board1Url,
    sceneSize: 720,
    boardRect: { left: 0.224, top: 0.255, width: 0.530, height: 0.520 },
  },
  {
    id: "oni",
    label: "Oni",
    imageUrl: board2Url,
    sceneSize: 720,
    boardRect: { left: 0.195, top: 0.203, width: 0.614, height: 0.608 },
  },
];

export function getSceneSkinById(id: string): GameSceneSkin | null {
  return GAME_SCENE_SKINS.find((s) => s.id === id) ?? null;
}
