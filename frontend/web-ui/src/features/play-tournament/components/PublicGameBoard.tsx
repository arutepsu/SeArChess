import { useMemo } from "react";
import type { BoardAnimation } from "../../../animation/animationTypes";
import type { PieceCode } from "../../../api/types";
import BoardSceneView from "../../game/components/BoardSceneView";
import { GAME_SCENE_SKINS } from "../../game/sceneSkins";
import { fenToBoardMatrix } from "../utils/fenToBoardMatrix";

interface PublicGameBoardProps {
  fen: string;
  lastMoveUci?: string;
  // Previous board FEN — used to determine which piece is moving and whether
  // a capture occurred, so the board can play the slide animation.
  prevFen?: string;
  // Monotonically increasing frame index used as the animation id.
  frameIndex?: number;
}

const DEFAULT_SCENE = GAME_SCENE_SKINS[0]; // oni

const NOOP_SELECT = () => {};
const NOOP_ANIM = () => {};

// Board convention (matches domain/fen.ts):
//   row 0 = rank 8, row 7 = rank 1
//   col 0 = file a, col 7 = file h
function squareToBoardCoords(sq: string): { row: number; col: number } | null {
  if (sq.length < 2) return null;
  const col = sq.charCodeAt(0) - 97; // 'a' = 97
  const rank = parseInt(sq[1], 10);
  if (isNaN(rank)) return null;
  const row = 8 - rank;
  if (col < 0 || col > 7 || row < 0 || row > 7) return null;
  return { row, col };
}

function buildBoardAnimation(
  prevFen: string,
  moveUci: string,
  id: number
): BoardAnimation | null {
  if (!moveUci || moveUci.length < 4) return null;
  const prevBoard = fenToBoardMatrix(prevFen);
  const from = moveUci.slice(0, 2);
  const to = moveUci.slice(2, 4);

  const fromCoords = squareToBoardCoords(from);
  const toCoords = squareToBoardCoords(to);
  if (!fromCoords || !toCoords) return null;

  const movingPiece = prevBoard[fromCoords.row][fromCoords.col] as PieceCode | null;
  if (!movingPiece) return null;

  const capturedPiece = prevBoard[toCoords.row][toCoords.col] as PieceCode | null;

  return {
    id,
    from,
    to,
    movingPiece,
    isCapture: Boolean(capturedPiece),
    ...(capturedPiece ? { capturedPiece } : {}),
  };
}

export default function PublicGameBoard({
  fen,
  lastMoveUci,
  prevFen,
  frameIndex,
}: PublicGameBoardProps) {
  const board = useMemo(() => fenToBoardMatrix(fen), [fen]);

  const lastMove = useMemo(
    () =>
      lastMoveUci && lastMoveUci.length >= 4
        ? { from: lastMoveUci.slice(0, 2), to: lastMoveUci.slice(2, 4) }
        : undefined,
    [lastMoveUci]
  );

  const animation = useMemo((): BoardAnimation | null => {
    if (!prevFen || !lastMoveUci || frameIndex === undefined || frameIndex === 0) return null;
    return buildBoardAnimation(prevFen, lastMoveUci, frameIndex);
  }, [prevFen, lastMoveUci, frameIndex]);

  return (
    <BoardSceneView
      gameScene={DEFAULT_SCENE}
      board={board}
      disabled={true}
      legalMoves={[]}
      animation={animation}
      lastMove={lastMove}
      onSelect={NOOP_SELECT}
      onAnimationFinished={NOOP_ANIM}
    />
  );
}
