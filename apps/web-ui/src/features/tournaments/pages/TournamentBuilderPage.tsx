import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createTournamentJob, fetchTournamentBots } from "../../../api/tournamentClient";
import type { BotSummary, CreateTournamentRequest } from "../../../api/tournamentTypes";
import ActionTile from "../../../components/ui/ActionTile";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import { tournamentErrorMessage } from "../components/tournamentUtils";
import "./TournamentBuilderPage.css";

function groupBots(bots: BotSummary[]): Array<[string, BotSummary[]]> {
  const grouped = new Map<string, BotSummary[]>();
  for (const bot of bots) {
    grouped.set(bot.family, [...(grouped.get(bot.family) ?? []), bot]);
  }
  return [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b));
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
                <ActionTile
                  key={bot.botId}
                  className="tournament-bot-tile"
                  label={bot.displayName}
                  description={`${bot.strategyType} / ${bot.botId}`}
                  badge={bot.engineType}
                  selected={isSelected}
                  disabled={!bot.available}
                  disabledReason={bot.unavailableReason ?? "Unavailable"}
                  ariaLabel={`${isSelected ? "Deselect" : "Select"} ${bot.displayName}`}
                  onClick={() => onToggle(bot.botId)}
                />
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

export default function TournamentBuilderPage() {
  const navigate = useNavigate();
  const [bots, setBots] = useState<BotSummary[]>([]);
  const [selectedBotIds, setSelectedBotIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const [name, setName] = useState("");
  const [mode, setMode] = useState<CreateTournamentRequest["mode"]>("double-round-robin");
  const [repetitions, setRepetitions] = useState(1);
  const [maxPly, setMaxPly] = useState(300);
  const [seed, setSeed] = useState("");

  const selectedCount = selectedBotIds.length;
  const canStart = selectedCount >= 2 && repetitions >= 1 && maxPly >= 1 && maxPly <= 1000 && !creating;

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    fetchTournamentBots()
      .then((data) => { if (active) setBots(data); })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : "Failed to load tournament bots"); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

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
      navigate(`/tournaments/${created.jobId}`);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : "Failed to create tournament");
    } finally {
      setCreating(false);
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
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap", flexShrink: 0 }}>
            <Button variant="secondary" size="sm" onClick={() => navigate("/tournaments/jobs")}>
              Recent jobs
            </Button>
            <Button variant="secondary" size="lg" onClick={() => navigate("/")}>
              Back
            </Button>
          </div>
        </div>

        {error && <ErrorState message={tournamentErrorMessage(error)} />}

        <div className="tournament-layout" style={{ gridTemplateColumns: "minmax(0, 1fr)" }}>
          <SectionCard className="tournament-card" title="Create Tournament">
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
        </div>
      </div>
    </div>
  );
}
