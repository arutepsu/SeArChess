import board1Url from "../../assets/boards/board1.jpg";
import board2Url from "../../assets/boards/board2.jpg";
import board3Url from "../../assets/boards/board3.jpg";
import board4Url from "../../assets/boards/board4.jpg";
import board5Url from "../../assets/boards/board5.jpg";
import board6Url from "../../assets/boards/board6.jpg";

export interface GameSceneSkin {
  id: string;
  label: string;
  imageUrl: string;
  sceneSize: number;
  /**
   * Position of the playable 8×8 board grid inside the full scene image.
   * All values are fractions of `sceneSize` (0..1).
   *
   * Calibration guide — tweak if the red debug grid drifts:
   *   left/top    : increase → grid moves right / down
   *   width/height: increase → grid grows; min(w,h) drives the square size
   */
  boardRect: {
    left: number;
    top: number;
    width: number;
    height: number;
  };
  /**
   * Per-scene piece calibration — only applied in transparent-overlay mode.
   * Edit here; Vite HMR reflects instantly with ?debugBoardScene in the URL.
   *
   *   pieceScale   : multiplier on PIECE_SCALE (2). 1.0 = default. Increase if
   *                  pieces look too small within their scene squares.
   *   pieceOffsetX : fractional shift of the square width, positive = right.
   *                  e.g. 0.05 shifts pieces 5 % of one square to the right.
   *   pieceOffsetY : fractional shift of the square height, positive = down.
   */
  pieceScale?: number;
  pieceOffsetX?: number;
  pieceOffsetY?: number;
}

export const GAME_SCENE_SKINS: GameSceneSkin[] = [
  {
    id: "oni",
    label: "Oni",
    imageUrl: board1Url,   // board1.jpg is the Oni scene
    sceneSize: 720,
    boardRect: { left: 0.180, top: 0.190, width: 0.640, height: 0.610 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
  {
    id: "sakura",
    label: "Sakura",
    imageUrl: board2Url,   // board2.jpg is the Sakura scene
    sceneSize: 720,
    boardRect: { left: 0.230, top: 0.260, width: 0.540, height: 0.525 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
  {
    id: "ocean",
    label: "Ocean",
    imageUrl: board3Url,   // board3.jpg is the Ocean scene
    sceneSize: 720,
    boardRect: { left: 0.195, top: 0.177, width: 0.610, height: 0.596 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
  {
    id: "shrine",
    label: "Shrine",
    imageUrl: board4Url,   // board4.jpg is the Shrine scene
    sceneSize: 720,
    boardRect: { left: 0.260, top: 0.237, width: 0.483, height: 0.482 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
  {
    id: "lantern",
    label: "Lantern",
    imageUrl: board5Url,   // board5.jpg is the Lantern scene
    sceneSize: 720,
    boardRect: { left: 0.245, top: 0.240, width: 0.513, height: 0.500 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
  {
    id: "heaven",
    label: "Heaven",
    imageUrl: board6Url,   // board6.jpg is the Heaven scene
    sceneSize: 720,
    boardRect: { left: 0.200, top: 0.200, width: 0.600, height: 0.600 },
    // Piece calibration — tune with ?debugBoardScene:
    pieceScale: 1.0,
    pieceOffsetX: 0.0,
    pieceOffsetY: 0.0,
  },
];

export function getSceneSkinById(id: string): GameSceneSkin | null {
  return GAME_SCENE_SKINS.find((s) => s.id === id) ?? null;
}
