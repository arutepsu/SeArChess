interface ErrorStateProps {
  message: string;
  className?: string;
}

export default function ErrorState({ message, className }: ErrorStateProps) {
  return (
    <p className={["ui-state-error", className].filter(Boolean).join(" ")}>{message}</p>
  );
}
