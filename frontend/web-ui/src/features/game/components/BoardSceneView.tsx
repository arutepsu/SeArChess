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
    return {
      ["--board-scene-image" as string]: `url(${gameScene.imageUrl})`,
      ["--board-rect-left-pct" as string]: `${(left * 100).toFixed(3)}%`,
      ["--board-rect-top-pct" as string]: `${(top * 100).toFixed(3)}%`,
      ["--board-rect-width-pct" as string]: `${(width * 100).toFixed(3)}%`,
      ["--board-rect-height-pct" as string]: `${(height * 100).toFixed(3)}%`,
      // Piece calibration vars — read by the debug label; transform applied via JS.
      ["--piece-scale" as string]: String(gameScene.pieceScale ?? 1),
      ["--piece-offset-x" as string]: String(gameScene.pieceOffsetX ?? 0),
      ["--piece-offset-y" as string]: String(gameScene.pieceOffsetY ?? 0),
    } as React.CSSProperties;
  }, [gameScene]);

  return (
    <div className="board-scene-container" style={containerStyle}>
      <div className="board-scene-grid-area">
        <ChessBoard
          {...chessBoardProps}
          transparentOverlay
          pieceScale={gameScene.pieceScale}
          pieceOffsetX={gameScene.pieceOffsetX}
          pieceOffsetY={gameScene.pieceOffsetY}
        />
      </div>

      {SCENE_DEBUG && (
        <div className="board-scene-debug" aria-hidden="true">
          <div className="board-scene-debug__rect" />
          <p className="board-scene-debug__label">
            {gameScene.id} · scene {gameScene.sceneSize}px
            {"\n"}left {gameScene.boardRect.left} · top {gameScene.boardRect.top}
            {"\n"}w {gameScene.boardRect.width} · h {gameScene.boardRect.height}
            {"\n"}pieceScale {gameScene.pieceScale ?? 1} · offsetX {gameScene.pieceOffsetX ?? 0} · offsetY {gameScene.pieceOffsetY ?? 0}
          </p>
        </div>
      )}
    </div>
  );
}
