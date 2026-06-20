import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listTournamentJobs } from "../../../api/tournamentClient";
import type { TournamentJobSummary } from "../../../api/tournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import TournamentJobsList from "../components/TournamentJobsList";
import { tournamentErrorMessage } from "../components/tournamentUtils";
import "./TournamentBuilderPage.css";

export default function TournamentJobsPage() {
  const navigate = useNavigate();
  const [jobs, setJobs] = useState<TournamentJobSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setJobs(await listTournamentJobs());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load tournament jobs");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="tournament-page">
      <div className="tournament-shell">
        <div className="tournament-header">
          <div>
            <h1 className="tournament-title">Recent Jobs</h1>
            <p className="tournament-subtitle">
              Click a job to view its status, progress, and analysis actions.
            </p>
          </div>
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap", flexShrink: 0 }}>
            <Button variant="secondary" size="sm" onClick={() => void load()}>
              Refresh
            </Button>
            <Button variant="secondary" size="lg" onClick={() => navigate("/tournaments")}>
              ← Builder
            </Button>
          </div>
        </div>

        {loading && <LoadingState message="Loading jobs…" />}
        {error && <ErrorState message={tournamentErrorMessage(error)} />}

        {!loading && !error && (
          <div className="tournament-layout" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
            <SectionCard className="tournament-card" title="All Jobs">
              <TournamentJobsList
                jobs={jobs}
                onSelect={(jobId) => navigate(`/tournaments/${jobId}`)}
              />
            </SectionCard>
          </div>
        )}
      </div>
    </div>
  );
}
