import type { TournamentAnalysisStatus, TournamentJobStatus, TournamentJobSummary } from "../../../api/tournamentTypes";

export const TERMINAL_STATUSES: TournamentJobStatus[] = ["succeeded", "failed", "cancelled"];

export function tournamentErrorMessage(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("failed") || lower.includes("fetch") || lower.includes("network")) {
    return "Tournament service unavailable. Start tournament-service on port 8085.";
  }
  return message;
}

export function formatDate(value: string | null): string {
  if (!value) return "not yet";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

export function progressLabel(
  job: Pick<TournamentJobSummary, "completedGames" | "plannedGames">
): string {
  return `${job.completedGames}/${job.plannedGames}`;
}

export function progressPct(
  job: Pick<TournamentJobSummary, "completedGames" | "plannedGames">
): number {
  if (job.plannedGames <= 0) return 0;
  return Math.max(0, Math.min(100, (job.completedGames / job.plannedGames) * 100));
}

export function StatusBadge({ status }: { status: TournamentJobStatus }) {
  return <span className={`tournament-status tournament-status--${status}`}>{status}</span>;
}

export function AnalysisBadge({ status }: { status: TournamentAnalysisStatus }) {
  return (
    <span className={`tournament-status tournament-analysis-status--${status}`}>
      {status.replace("_", " ")}
    </span>
  );
}
