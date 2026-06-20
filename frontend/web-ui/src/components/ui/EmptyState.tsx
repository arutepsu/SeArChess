interface EmptyStateProps {
  message?: string;
  className?: string;
}

export default function EmptyState({ message = "No data available.", className }: EmptyStateProps) {
  return (
    <p className={["ui-state-empty", className].filter(Boolean).join(" ")}>{message}</p>
  );
}
