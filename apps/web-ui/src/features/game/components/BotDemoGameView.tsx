import ChessBoard from "../../../components/chessBoard";
import Button from "../../../components/ui/Button";
import type { GameState, PlayerColor } from "../../../api/types";
import "./BotDemoGameView.css";

interface BotDemoGameViewProps {
  mappedBotGame: GameState | null;
  orientation: PlayerColor;
  onBackToGame: () => void;
}

export default function BotDemoGameView({ mappedBotGame, orientation, onBackToGame }: BotDemoGameViewProps) {
  const backButton = (
    <div className="bot-demo-header">
      <Button variant="ghost" onClick={onBackToGame}>
        ← Back to Game
      </Button>
    </div>
  );

  if (!mappedBotGame) {
    return (
      <section className="board-shell placeholder">
        {backButton}
        <div className="loading">Waiting for bot game data...</div>
      </section>
    );
  }

  return (
    <>
      {backButton}
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
    </>
  );
}
