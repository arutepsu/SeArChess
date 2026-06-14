interface LoadingStateProps {
  message?: string;
  className?: string;
}

export default function LoadingState({ message = "Loading…", className }: LoadingStateProps) {
  return (
    <p className={["ui-state-loading", className].filter(Boolean).join(" ")}>{message}</p>
  );
}
