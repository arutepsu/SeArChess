import { useMemo } from "react";
import type { GameSceneSkin } from "../sceneSkins";
import ChessBoard from "../../../components/chessBoard";
import type { ChessBoardProps } from "../../../components/chessBoard";
import "./BoardSceneView.css";

// DEV ONLY — add ?debugBoardScene to any game URL to reveal the playable-area overlay.
// http://localhost:5173/game?debugBoardScene
const SCENE_DEBUG =
  typeof window !== "undefined" &&
  new URLSearchParams(window.location.search).has("debugBoardScene");

interface BoardSceneViewProps extends ChessBoardProps {
  gameScene: GameSceneSkin;
}

export default function BoardSceneView({ gameScene, ...chessBoardProps }: BoardSceneViewProps) {
  const containerStyle = useMemo((): React.CSSProperties => {
    const { left, top, width, height } = gameScene.boardRect;
    const scenePx = gameScene.sceneSize;
    // Force a square grid (min of w/h) so squareSize maps both axes correctly.
    const boardSizePx = Math.round(Math.min(width, height) * scenePx);
    return {
      ["--board-scene-image" as string]: `url(${gameScene.imageUrl})`,
      ["--board-rect-left" as string]: `${Math.round(left * scenePx)}px`,
      ["--board-rect-top" as string]: `${Math.round(top * scenePx)}px`,
      ["--board-rect-size" as string]: `${boardSizePx}px`,
      width: `${scenePx}px`,
      height: `${scenePx}px`,
    } as React.CSSProperties;
  }, [gameScene]);

  return (
    <div className="board-scene-container" style={containerStyle}>
      <div className="board-scene-grid-area">
        <ChessBoard {...chessBoardProps} transparentOverlay />
      </div>

      {SCENE_DEBUG && (
        <div className="board-scene-debug" aria-hidden="true">
          <div className="board-scene-debug__rect" />
          <p className="board-scene-debug__label">
            {gameScene.id} · scene {gameScene.sceneSize}px
            {"\n"}left {gameScene.boardRect.left} · top {gameScene.boardRect.top}
            {"\n"}w {gameScene.boardRect.width} · h {gameScene.boardRect.height}
          </p>
        </div>
      )}
    </div>
  );
}
