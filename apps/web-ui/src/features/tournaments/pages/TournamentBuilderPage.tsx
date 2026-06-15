import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  analyzeTournament,
  cancelTournamentJob,
  createTournamentJob,
  fetchTournamentBots,
  fetchTournamentJob,
  listTournamentJobs,
} from "../../../api/tournamentClient";
import type {
  BotSummary,
  CreateTournamentRequest,
  TournamentJobDetails,
  TournamentJobStatus,
  TournamentJobSummary,
} from "../../../api/tournamentTypes";
import Button from "../../../components/ui/Button";
import EmptyState from "../../../components/ui/EmptyState";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import "./TournamentBuilderPage.css";

const TERMINAL_STATUSES: TournamentJobStatus[] = ["succeeded", "failed", "cancelled"];

function tournamentErrorMessage(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("failed") || lower.includes("fetch") || lower.includes("network")) {
    return "Tournament service unavailable. Start tournament-service on port 8085.";
  }
  return message;
}

function formatDate(value: string | null): string {
  if (!value) return "not yet";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

function progressLabel(job: Pick<TournamentJobSummary, "completedGames" | "plannedGames">): string {
  return `${job.completedGames}/${job.plannedGames}`;
}

function progressPct(job: Pick<TournamentJobSummary, "completedGames" | "plannedGames">): number {
  if (job.plannedGames <= 0) return 0;
  return Math.max(0, Math.min(100, (job.completedGames / job.plannedGames) * 100));
}

function groupBots(bots: BotSummary[]): Array<[string, BotSummary[]]> {
  const grouped = new Map<string, BotSummary[]>();
  for (const bot of bots) {
    grouped.set(bot.family, [...(grouped.get(bot.family) ?? []), bot]);
  }
  return [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b));
}

function StatusBadge({ status }: { status: TournamentJobStatus }) {
  return <span className={`tournament-status tournament-status--${status}`}>{status}</span>;
}

function AnalysisBadge({ status }: { status: TournamentJobDetails["analysisStatus"] }) {
  return <span className={`tournament-status tournament-analysis-status--${status}`}>{status.replace("_", " ")}</span>;
}

interface BotSelectorProps {
  bots: BotSummary[];
  selectedBotIds: string[];
  onToggle: (botId: string) => void;
}

function BotSelector({ bots, selectedBotIds, onToggle }: BotSelectorProps) {
  const selected = new Set(selectedBotIds);
  return (
    <div className="tournament-bot-groups">
      {groupBots(bots).map(([family, familyBots]) => (
        <div className="tournament-bot-family" key={family}>
          <h3>{family}</h3>
          <div className="tournament-bot-grid">
            {familyBots.map((bot) => {
              const isSelected = selected.has(bot.botId);
              return (
                <button
                  type="button"
                  key={bot.botId}
                  className={[
                    "tournament-bot-card",
                    isSelected ? "tournament-bot-card--selected" : "",
                    bot.available ? "" : "tournament-bot-card--disabled",
                  ].filter(Boolean).join(" ")}
                  disabled={!bot.available}
                  onClick={() => onToggle(bot.botId)}
                >
                  <span className="tournament-bot-card__name">{bot.displayName}</span>
                  <span className="tournament-bot-card__meta">
                    {bot.strategyType} / {bot.engineType}
                  </span>
                  <span className="tournament-bot-card__id">{bot.botId}</span>
                  {!bot.available && (
                    <span className="tournament-bot-card__reason">
                      {bot.unavailableReason ?? "Unavailable"}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

interface JobListProps {
  jobs: TournamentJobSummary[];
  selectedJobId: string | null;
  onSelect: (jobId: string) => void;
}

function JobList({ jobs, selectedJobId, onSelect }: JobListProps) {
  if (jobs.length === 0) return <EmptyState />;

  return (
    <div className="tournament-job-list">
      {jobs.map((job) => (
        <button
          type="button"
          key={job.jobId}
          className={[
            "tournament-job-row",
            selectedJobId === job.jobId ? "tournament-job-row--selected" : "",
          ].filter(Boolean).join(" ")}
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

interface JobDetailsProps {
  job: TournamentJobDetails | null;
  loading: boolean;
  error: string | null;
  cancelling: boolean;
  analyzing: boolean;
  onCancel: () => void;
  onAnalyze: () => void;
}

function JobDetails({ job, loading, error, cancelling, analyzing, onCancel, onAnalyze }: JobDetailsProps) {
  if (loading) return <LoadingState message="Loading job..." />;
  if (error) return <ErrorState message={tournamentErrorMessage(error)} />;
  if (!job) return <EmptyState message="Select a job to inspect its status." />;

  const canCancel = job.status === "queued" || job.status === "running";
  const canAnalyze = job.status === "succeeded" && job.analysisStatus !== "queued" && job.analysisStatus !== "running";

  return (
    <div className="tournament-detail">
      <div className="tournament-detail__header">
        <div>
          <h3>{job.name ?? "Untitled tournament"}</h3>
          <p>{job.jobId}</p>
        </div>
        <StatusBadge status={job.status} />
      </div>

      <div className="tournament-progress tournament-progress--large">
        <span style={{ width: `${progressPct(job)}%` }} />
      </div>

      <dl className="tournament-detail-grid">
        <div><dt>Progress</dt><dd>{progressLabel(job)} games</dd></div>
        <div><dt>Mode</dt><dd>{job.mode}</dd></div>
        <div><dt>Repetitions</dt><dd>{job.repetitions}</dd></div>
        <div><dt>Max ply</dt><dd>{job.maxPly}</dd></div>
        <div><dt>Created</dt><dd>{formatDate(job.createdAt)}</dd></div>
        <div><dt>Finished</dt><dd>{formatDate(job.finishedAt)}</dd></div>
        <div><dt>Analysis status</dt><dd><AnalysisBadge status={job.analysisStatus} /></dd></div>
        <div><dt>Analytics run</dt><dd>{job.analyticsRunId ?? "not analyzed yet"}</dd></div>
        <div><dt>Analytics output</dt><dd>{job.analyticsOutputPath ?? "not written yet"}</dd></div>
        <div><dt>Output</dt><dd>{job.outputPath ?? "not written yet"}</dd></div>
      </dl>

      {job.status === "succeeded" && job.outputPath && (
        <p className="tournament-success">JSONL output is ready at {job.outputPath}.</p>
      )}
      {job.errorMessage && <ErrorState message={job.errorMessage} />}
      {job.analyticsErrorMessage && <ErrorState message={job.analyticsErrorMessage} />}
      {job.resultSummary && <p className="tournament-note">{job.resultSummary}</p>}
      {job.analysisStatus === "succeeded" && (
        <p className="tournament-success">
          Analytics written. <a href="/analytics">Open /analytics</a> and select the new run.
        </p>
      )}

      <div className="tournament-detail-actions">
        {canAnalyze && (
          <Button variant="primary" size="sm" onClick={onAnalyze} disabled={analyzing}>
            {analyzing ? "Queueing..." : "Run analytics"}
          </Button>
        )}
        {canCancel && (
          <Button variant="danger" size="sm" onClick={onCancel} disabled={cancelling}>
            {cancelling ? "Cancelling..." : "Cancel job"}
          </Button>
        )}
      </div>
    </div>
  );
}

export default function TournamentBuilderPage() {
  const navigate = useNavigate();
  const [bots, setBots] = useState<BotSummary[]>([]);
  const [jobs, setJobs] = useState<TournamentJobSummary[]>([]);
  const [selectedBotIds, setSelectedBotIds] = useState<string[]>([]);
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<TournamentJobDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [jobsLoading, setJobsLoading] = useState(true);
  const [jobLoading, setJobLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [jobError, setJobError] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);

  const [name, setName] = useState("");
  const [mode, setMode] = useState<CreateTournamentRequest["mode"]>("double-round-robin");
  const [repetitions, setRepetitions] = useState(1);
  const [maxPly, setMaxPly] = useState(300);
  const [seed, setSeed] = useState("");

  const selectedCount = selectedBotIds.length;
  const canStart = selectedCount >= 2 && repetitions >= 1 && maxPly >= 1 && maxPly <= 1000 && !creating;
  const terminal = selectedJob ? TERMINAL_STATUSES.includes(selectedJob.status) : true;
  const analysisActive = selectedJob?.analysisStatus === "queued" || selectedJob?.analysisStatus === "running";

  const refreshJobs = useCallback(async () => {
    setJobsLoading(true);
    try {
      setJobs(await listTournamentJobs());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load tournament jobs");
    } finally {
      setJobsLoading(false);
    }
  }, []);

  const loadJob = useCallback(async (jobId: string, quiet = false) => {
    if (!quiet) setJobLoading(true);
    setJobError(null);
    try {
      const job = await fetchTournamentJob(jobId);
      setSelectedJob(job);
      setSelectedJobId(job.jobId);
    } catch (e) {
      setJobError(e instanceof Error ? e.message : "Failed to load tournament job");
    } finally {
      if (!quiet) setJobLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);

    Promise.allSettled([fetchTournamentBots(), listTournamentJobs()])
      .then(([botResult, jobResult]) => {
        if (!active) return;
        if (botResult.status === "fulfilled") setBots(botResult.value);
        else setError(botResult.reason instanceof Error ? botResult.reason.message : "Failed to load tournament bots");
        if (jobResult.status === "fulfilled") setJobs(jobResult.value);
        else setError(jobResult.reason instanceof Error ? jobResult.reason.message : "Failed to load tournament jobs");
      })
      .finally(() => {
        if (!active) return;
        setLoading(false);
        setJobsLoading(false);
      });

    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!selectedJobId || (terminal && !analysisActive)) return;
    const id = window.setInterval(() => {
      void loadJob(selectedJobId, true).then(refreshJobs);
    }, 2000);
    return () => window.clearInterval(id);
  }, [analysisActive, loadJob, refreshJobs, selectedJobId, terminal]);

  const toggleBot = (botId: string) => {
    setSelectedBotIds((current) =>
      current.includes(botId) ? current.filter((id) => id !== botId) : [...current, botId]
    );
  };

  const handleCreate = async () => {
    if (!canStart) return;
    setCreating(true);
    setCreateError(null);
    try {
      const request: CreateTournamentRequest = {
        name: name.trim() || undefined,
        botIds: selectedBotIds,
        mode,
        repetitions,
        maxPly,
        seed: seed.trim() ? Number(seed) : undefined,
      };
      const created = await createTournamentJob(request);
      await refreshJobs();
      await loadJob(created.jobId);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : "Failed to create tournament");
    } finally {
      setCreating(false);
    }
  };

  const handleCancel = async () => {
    if (!selectedJobId) return;
    setCancelling(true);
    try {
      const job = await cancelTournamentJob(selectedJobId);
      setSelectedJob(job);
      await refreshJobs();
    } catch (e) {
      setJobError(e instanceof Error ? e.message : "Failed to cancel tournament job");
    } finally {
      setCancelling(false);
    }
  };

  const handleAnalyze = async () => {
    if (!selectedJobId) return;
    setAnalyzing(true);
    setJobError(null);
    try {
      await analyzeTournament(selectedJobId);
      await loadJob(selectedJobId, true);
      await refreshJobs();
    } catch (e) {
      setJobError(e instanceof Error ? e.message : "Failed to queue analytics");
    } finally {
      setAnalyzing(false);
    }
  };

  const availableCount = useMemo(() => bots.filter((bot) => bot.available).length, [bots]);

  return (
    <div className="tournament-page">
      <div className="tournament-shell">
        <div className="tournament-header">
          <div>
            <h1 className="tournament-title">Tournament Builder</h1>
            <p className="tournament-subtitle">
              Create tournament event files, then run Spark analytics when a job succeeds.
            </p>
          </div>
          <Button variant="secondary" size="lg" onClick={() => navigate("/")}>
            Back
          </Button>
        </div>

        {error && <ErrorState message={tournamentErrorMessage(error)} />}

        <div className="tournament-layout">
          <SectionCard className="tournament-card tournament-builder-card" title="Create Tournament">
            {loading ? (
              <LoadingState message="Loading tournament bots..." />
            ) : (
              <>
                <div className="tournament-form-grid">
                  <label>
                    <span>Name</span>
                    <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Heuristic smoke" />
                  </label>
                  <label>
                    <span>Mode</span>
                    <select value={mode} onChange={(e) => setMode(e.target.value as CreateTournamentRequest["mode"])}>
                      <option value="double-round-robin">Double round-robin</option>
                    </select>
                  </label>
                  <label>
                    <span>Repetitions</span>
                    <input
                      type="number"
                      min={1}
                      value={repetitions}
                      onChange={(e) => setRepetitions(Number(e.target.value))}
                    />
                  </label>
                  <label>
                    <span>Max ply</span>
                    <input
                      type="number"
                      min={1}
                      max={1000}
                      value={maxPly}
                      onChange={(e) => setMaxPly(Number(e.target.value))}
                    />
                  </label>
                  <label>
                    <span>Seed</span>
                    <input
                      type="number"
                      value={seed}
                      onChange={(e) => setSeed(e.target.value)}
                      placeholder="optional"
                    />
                  </label>
                </div>

                <div className="tournament-selector-header">
                  <span>{selectedCount} selected</span>
                  <span>{availableCount} available</span>
                </div>
                <BotSelector bots={bots} selectedBotIds={selectedBotIds} onToggle={toggleBot} />

                {selectedCount < 2 && (
                  <p className="tournament-note">Select at least two available bots to start a tournament.</p>
                )}
                {createError && <ErrorState message={createError} />}
                <Button variant="primary" size="lg" onClick={handleCreate} disabled={!canStart}>
                  {creating ? "Starting..." : "Start tournament"}
                </Button>
              </>
            )}
          </SectionCard>

          <SectionCard className="tournament-card" title="Recent Jobs">
            {jobsLoading ? (
              <LoadingState message="Loading jobs..." />
            ) : (
              <JobList jobs={jobs} selectedJobId={selectedJobId} onSelect={(jobId) => void loadJob(jobId)} />
            )}
          </SectionCard>

          <SectionCard className="tournament-card tournament-detail-card" title="Job Details">
            <JobDetails
              job={selectedJob}
              loading={jobLoading}
              error={jobError}
              cancelling={cancelling}
              analyzing={analyzing}
              onCancel={handleCancel}
              onAnalyze={handleAnalyze}
            />
          </SectionCard>
        </div>
      </div>
    </div>
  );
}
