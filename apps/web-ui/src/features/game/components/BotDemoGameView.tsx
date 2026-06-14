import ChessBoard from "../../../components/chessBoard";
import type { GameState, PlayerColor } from "../../../api/types";

interface BotDemoGameViewProps {
  mappedBotGame: GameState | null;
  orientation: PlayerColor;
}

export default function BotDemoGameView({ mappedBotGame, orientation }: BotDemoGameViewProps) {
  if (!mappedBotGame) {
    return (
      <section className="board-shell placeholder">
        <div className="loading">Warte auf Bot-Spieldaten... / Waiting for bot game data...</div>
      </section>
    );
  }

  return (
    <ChessBoard
      board={mappedBotGame.board}
      selectedSquare={undefined}
      legalMoves={[]}
      animation={null}
      idleAnimation={true}
      disabled={true}
      onSelect={() => {}}
      onAnimationFinished={() => {}}
      inCheck={mappedBotGame.status === "check"}
      activeColor={mappedBotGame.activeColor}
      gameStatus={mappedBotGame.status}
      drawReason={mappedBotGame.drawReason}
      winner={mappedBotGame.winner}
      promotionPending={null}
      onResolvePromotion={() => {}}
      onCancelPromotion={() => {}}
      onNewGame={() => {}}
      orientation={orientation}
    />
  );
}
