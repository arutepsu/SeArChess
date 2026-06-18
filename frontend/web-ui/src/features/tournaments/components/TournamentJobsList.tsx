import type { TournamentJobSummary } from "../../../api/tournamentTypes";
import EmptyState from "../../../components/ui/EmptyState";
import { formatDate, progressLabel, progressPct, StatusBadge } from "./tournamentUtils";

interface TournamentJobsListProps {
  jobs: TournamentJobSummary[];
  onSelect: (jobId: string) => void;
}

export default function TournamentJobsList({ jobs, onSelect }: TournamentJobsListProps) {
  if (jobs.length === 0) return <EmptyState />;

  return (
    <div className="tournament-job-list">
      {jobs.map((job) => (
        <button
          type="button"
          key={job.jobId}
          className="tournament-job-row"
          onClick={() => onSelect(job.jobId)}
        >
          <div className="tournament-job-row__main">
            <span className="tournament-job-row__name">{job.name ?? "Untitled tournament"}</span>
            <span className="tournament-job-row__id">{job.jobId.slice(0, 8)}</span>
          </div>
          <StatusBadge status={job.status} />
          <div className="tournament-progress" aria-label={`Progress ${progressLabel(job)}`}>
            <span style={{ width: `${progressPct(job)}%` }} />
          </div>
          <div className="tournament-job-row__meta">
            <span>{progressLabel(job)} games</span>
            <span>{formatDate(job.createdAt)}</span>
            <span>{job.finishedAt ? `finished ${formatDate(job.finishedAt)}` : "not finished"}</span>
          </div>
          <div className="tournament-job-row__bots">{job.selectedBotIds.join(", ")}</div>
        </button>
      ))}
    </div>
  );
}
