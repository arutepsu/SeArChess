interface GameModeCardProps {
  title: string;
  description: string;
  buttonLabel: string;
  onAction: () => void;
  busy?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  info?: string;
}

export default function GameModeCard({
  title,
  description,
  buttonLabel,
  onAction,
  busy = false,
  disabled = false,
  disabledReason,
  info,
}: GameModeCardProps) {
  const isDisabled = busy || disabled;

  return (
    <article className="game-mode-card">
      <h2 className="game-mode-card-title">{title}</h2>
      <p className="game-mode-card-desc">{description}</p>
      {info ? (
        <p className="game-mode-card-info">{info}</p>
      ) : (
        <>
          {isDisabled && disabledReason && (
            <p className="game-mode-card-disabled-reason">{disabledReason}</p>
          )}
          <button
            type="button"
            className="game-mode-card-btn"
            disabled={isDisabled}
            onClick={onAction}
          >
            {buttonLabel}
          </button>
        </>
      )}
    </article>
  );
}
