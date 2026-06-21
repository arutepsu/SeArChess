import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { getPublicTournamentAnalyticsExport } from "../../../api/publicTournamentClient";
import type { PublicAnalyticsExport } from "../../../api/publicTournamentTypes";
import { isAnalyticsNotReady } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import AvgGameLengthChart from "../../analytics/components/charts/AvgGameLengthChart";
import BotFamilyChart from "../../analytics/components/charts/BotFamilyChart";
import ColorPerformanceChart from "../../analytics/components/charts/ColorPerformanceChart";
import FastestWinsChart from "../../analytics/components/charts/FastestWinsChart";
import LeaderboardChart from "../../analytics/components/charts/LeaderboardChart";
import StrategyChart from "../../analytics/components/charts/StrategyChart";
import TerminationsChart from "../../analytics/components/charts/TerminationsChart";
import { adaptPublicTournamentAnalytics } from "../analytics/publicTournamentAnalyticsAdapter";
import "./PlayTournament.css";

type LoadState =
  | { status: "loading" }
  | { status: "notReady"; message: string }
  | { status: "ready"; data: PublicAnalyticsExport }
  | { status: "error"; message: string };

function allSameValue<T>(items: T[], key: (t: T) => string | null): boolean {
  if (items.length === 0) return true;
  const first = key(items[0]);
  return items.every((i) => key(i) === first);
}

export default function PublicTournamentAnalyticsPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [state, setState] = useState<LoadState>({ status: "loading" });

  useEffect(() => {
    if (!id) return;
    let active = true;
    setState({ status: "loading" });
    getPublicTournamentAnalyticsExport(id)
      .then((r) => {
        if (!active) return;
        if (isAnalyticsNotReady(r)) {
          setState({ status: "notReady", message: r.message });
        } else {
          setState({ status: "ready", data: r });
        }
      })
      .catch((e: unknown) => {
        if (!active) return;
        setState({ status: "error", message: e instanceof Error ? e.message : "Failed to load analytics" });
      });
    return () => { active = false; };
  }, [id]);

  const normalized = useMemo(() => {
    if (state.status !== "ready") return null;
    return adaptPublicTournamentAnalytics(state.data);
  }, [state]);

  const allFamiliesUnknown =
    normalized !== null && allSameValue(normalized.botFamilies, (r) => r.family === "(unknown)" ? "(unknown)" : null);
  const allStrategiesUnknown =
    normalized !== null && allSameValue(normalized.strategies, (r) => r.strategyType === "(unknown)" ? "(unknown)" : null);

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Tournament Server Analytics</h1>
            <p className="play-tournament-subtitle">
              Derived from public tournament export — read-only.
            </p>
          </div>
          <Button
            variant="secondary"
            size="lg"
            onClick={() => navigate(`/play-tournament/${id ?? ""}`)}
          >
            ← Tournament
          </Button>
        </div>

        {state.status === "loading" && <LoadingState message="Loading analytics export…" />}

        {state.status === "notReady" && (
          <div className="play-tournament-not-ready">
            Analytics are available after the tournament finishes.
            {state.message && <span style={{ display: "block", marginTop: 8, opacity: 0.7 }}>{state.message}</span>}
          </div>
        )}

        {state.status === "error" && <ErrorState message={state.message} />}

        {state.status === "ready" && normalized !== null && (
          <>
            <SectionCard title="Leaderboard — Tournament Server">
              <LeaderboardChart rows={normalized.leaderboard} />
            </SectionCard>

            {!allFamiliesUnknown && (
              <SectionCard title="Bot Family Comparison">
                <BotFamilyChart rows={normalized.botFamilies} />
              </SectionCard>
            )}

            {!allStrategiesUnknown && (
              <SectionCard title="Strategy Comparison">
                <StrategyChart rows={normalized.strategies} />
              </SectionCard>
            )}

            <SectionCard title="Termination Reasons">
              <TerminationsChart rows={normalized.terminations} />
            </SectionCard>

            <SectionCard title="Fastest Winning Bots">
              <FastestWinsChart rows={normalized.fastestWins} />
            </SectionCard>

            <SectionCard title="Color Performance">
              <ColorPerformanceChart rows={normalized.colorPerformance} />
            </SectionCard>

            <SectionCard title="Average Game Length by Pairing">
              <AvgGameLengthChart rows={normalized.avgGameLength} />
            </SectionCard>

            <p className="play-tournament-info-label" style={{ marginTop: 8, textAlign: "center" }}>
              Elo ratings, Searchess AI comparison, and Stockfish comparison are not available
              in the tournament-server export.
            </p>
          </>
        )}
      </div>
    </div>
  );
}
