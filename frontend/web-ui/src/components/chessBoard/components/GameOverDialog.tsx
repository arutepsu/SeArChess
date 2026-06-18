import Button from "../../ui/Button";

interface GameOverDialogProps {
  title: string;
  message: string;
  onNewGame?: () => void;
  onDismiss: () => void;
}

export default function GameOverDialog({
  title,
  message,
  onNewGame,
  onDismiss,
}: GameOverDialogProps) {
  return (
    <div className="game-over-overlay animate-fade-in" role="presentation">
      <div
        className="game-over-dialog panel animate-scale-up"
        role="dialog"
        aria-modal="true"
        aria-labelledby="game-over-dialog-title"
        aria-describedby="game-over-dialog-message"
      >
        <h3 id="game-over-dialog-title">{title}</h3>
        <p id="game-over-dialog-message" className="game-over-message">{message}</p>
        <div className="game-over-actions">
          {onNewGame && (
            <Button variant="primary" onClick={onNewGame}>
              New Game
            </Button>
          )}
          <Button variant="secondary" onClick={onDismiss}>
            Analyse Board
          </Button>
        </div>
      </div>
    </div>
  );
}
