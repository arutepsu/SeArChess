import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { analyzeTournament, fetchTournamentJob } from "../../../api/tournamentClient";
import type { TournamentJobDetails } from "../../../api/tournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import {
  AnalysisBadge,
  formatDate,
  progressPct,
  StatusBadge,
  TERMINAL_STATUSES,
} from "../components/tournamentUtils";
import "./TournamentBuilderPage.css";

export default function TournamentRunningPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();
  const [job, setJob] = useState<TournamentJobDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeError, setAnalyzeError] = useState<string | null>(null);

  const load = useCallback(async (quiet = false) => {
    if (!jobId) return;
    if (!quiet) setLoading(true);
    try {
      const data = await fetchTournamentJob(jobId);
      setJob(data);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load tournament job");
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [jobId]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!job) return;
    const isTerminal = TERMINAL_STATUSES.includes(job.status);
    const analysisActive = job.analysisStatus === "queued" || job.analysisStatus === "running";
    if (isTerminal && !analysisActive) return;
    const id = window.setInterval(() => { void load(true); }, 2000);
    return () => window.clearInterval(id);
  }, [job, load]);

  const handleAnalyze = async () => {
    if (!jobId) return;
    setAnalyzing(true);
    setAnalyzeError(null);
    try {
      await analyzeTournament(jobId);
      await load(true);
    } catch (e) {
      setAnalyzeError(e instanceof Error ? e.message : "Failed to queue analytics");
    } finally {
      setAnalyzing(false);
    }
  };

  const isTerminal = job ? TERMINAL_STATUSES.includes(job.status) : false;
  const analyticsReady = job?.analysisStatus === "succeeded" && Boolean(job.analyticsRunId);
  const canAnalyze =
    job?.status === "succeeded" &&
    job.analysisStatus !== "queued" &&
    job.analysisStatus !== "running" &&
    job.analysisStatus !== "succeeded";
  const analysisInProgress =
    job?.analysisStatus === "queued" || job?.analysisStatus === "running";

  return (
    <div className="tournament-page">
      <div className="tournament-shell">
        <div className="tournament-header">
          <div>
            <h1 className="tournament-title">
              {loading && !job ? "Loading…" : (job?.name ?? "Untitled tournament")}
            </h1>
            {job && (
              <p className="tournament-subtitle" style={{ fontFamily: '"Courier New", monospace', fontSize: "0.82rem" }}>
                {job.jobId}
              </p>
            )}
          </div>
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap", flexShrink: 0 }}>
            <Button variant="secondary" size="sm" onClick={() => navigate("/tournaments/jobs")}>
              Recent jobs
            </Button>
            <Button variant="secondary" size="lg" onClick={() => navigate("/tournaments")}>
              ← Builder
            </Button>
            {analyticsReady && (
              <Button
                variant="primary"
                size="lg"
                onClick={() => navigate(`/analytics?runId=${encodeURIComponent(job!.analyticsRunId!)}`)}
              >
                Open Analytics
              </Button>
            )}
          </div>
        </div>

        {loading && !job && <LoadingState message="Loading tournament…" />}
        {error && <ErrorState message={error} />}

        {job && (
          <div className="tournament-layout" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
            <SectionCard className="tournament-card" title="Tournament Status">
              <div style={{ display: "flex", alignItems: "center", gap: "14px", marginBottom: "14px", flexWrap: "wrap" }}>
                <StatusBadge status={job.status} />
                {!isTerminal && (
                  <span style={{ fontSize: "0.82rem", opacity: 0.65 }}>Polling every 2 s…</span>
                )}
              </div>

              <div className="tournament-progress tournament-progress--large">
                <span style={{ width: `${progressPct(job)}%` }} />
              </div>
              <p style={{ margin: "0 0 18px", fontSize: "0.9rem" }}>
                {job.completedGames} / {job.plannedGames} games completed
              </p>

              <dl className="tournament-detail-grid">
                <div><dt>Mode</dt><dd>{job.mode}</dd></div>
                <div><dt>Repetitions</dt><dd>{job.repetitions}</dd></div>
                <div><dt>Max ply</dt><dd>{job.maxPly}</dd></div>
                <div><dt>Created</dt><dd>{formatDate(job.createdAt)}</dd></div>
                <div><dt>Finished</dt><dd>{formatDate(job.finishedAt)}</dd></div>
                <div>
                  <dt>Analysis</dt>
                  <dd><AnalysisBadge status={job.analysisStatus} /></dd>
                </div>
                {job.analyticsRunId && (
                  <div><dt>Analytics run</dt><dd>{job.analyticsRunId}</dd></div>
                )}
              </dl>

              {job.selectedBotIds.length > 0 && (
                <div style={{ marginTop: "16px" }}>
                  <p style={{ margin: "0 0 8px", fontSize: "0.78rem", color: "var(--accent)", textTransform: "uppercase", letterSpacing: "0.04em", fontWeight: 600 }}>
                    Bots ({job.selectedBotIds.length})
                  </p>
                  <div style={{ display: "flex", flexWrap: "wrap", gap: "6px" }}>
                    {job.selectedBotIds.map((id) => (
                      <span key={id} className="tournament-status" style={{ fontFamily: '"Courier New", monospace' }}>
                        {id}
                      </span>
                    ))}
                  </div>
                </div>
              )}

              {job.errorMessage && (
                <div style={{ marginTop: "14px" }}>
                  <ErrorState message={job.errorMessage} />
                </div>
              )}
              {job.analyticsErrorMessage && (
                <div style={{ marginTop: "14px" }}>
                  <ErrorState message={job.analyticsErrorMessage} />
                </div>
              )}
              {analyzeError && (
                <div style={{ marginTop: "14px" }}>
                  <ErrorState message={analyzeError} />
                </div>
              )}
              {job.resultSummary && (
                <p className="tournament-note" style={{ marginTop: "12px" }}>{job.resultSummary}</p>
              )}
              {job.status === "succeeded" && job.outputPath && (
                <p className="tournament-success" style={{ marginTop: "12px" }}>
                  JSONL output ready at {job.outputPath}.
                </p>
              )}
              {analyticsReady && (
                <p className="tournament-success" style={{ marginTop: "12px" }}>
                  Analytics written.{" "}
                  <button
                    type="button"
                    onClick={() => navigate(`/analytics?runId=${encodeURIComponent(job.analyticsRunId!)}`)}
                    style={{ background: "none", border: "none", color: "#d8ecff", cursor: "pointer", fontWeight: 700, padding: 0, font: "inherit" }}
                  >
                    Open /analytics
                  </button>
                  {" "}and select the new run.
                </p>
              )}

              {(canAnalyze || analysisInProgress) && (
                <div className="tournament-detail-actions" style={{ marginTop: "16px" }}>
                  {canAnalyze && (
                    <Button variant="primary" size="sm" onClick={handleAnalyze} disabled={analyzing}>
                      {analyzing ? "Queueing…" : "Run analysis"}
                    </Button>
                  )}
                  {analysisInProgress && (
                    <span style={{ display: "flex", alignItems: "center", gap: "8px", fontSize: "0.85rem", opacity: 0.75 }}>
                      Analysis <AnalysisBadge status={job.analysisStatus} /> — polling…
                    </span>
                  )}
                </div>
              )}
            </SectionCard>
          </div>
        )}
      </div>
    </div>
  );
}
