import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  createPublicTournament,
  joinPublicTournamentWithBot,
  listPublicOpenings,
} from "../../../api/publicTournamentClient";
import type {
  CreatePublicTournamentRequest,
  PublicTournamentFormat,
  PublicOpening,
} from "../../../api/publicTournamentTypes";
import {
  getMyTournamentBots,
  recordTournamentHost,
  recordTournamentParticipant,
} from "../../../api/userServiceClient";
import type { OwnedTournamentBot } from "../../../api/userServiceTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import "./PlayTournament.css";

// Searchess catalog bot entries from the server-side BotRegistry.
// TODO(Step 2): Replace this hardcoded list with a GET /api/bots/catalog endpoint
// so new catalog bots appear automatically without a frontend deploy.
// TODO(Step 2): Implement system-bot registration — each catalog bot must be
// registered on the Tournament Server (POST /api/auth/register) and its
// tournamentServerBotId recorded alongside searchessCatalogBotId so the
// PublicTournamentBotRunner can drive it.
const SEARCHESS_CATALOG_BOTS = [
  { id: "random-bot",        name: "Random Bot",        desc: "Picks a legal move at random" },
  { id: "capture-first",     name: "Capture First",     desc: "Prefers captures; otherwise random" },
  { id: "material-greedy",   name: "Material Greedy",   desc: "Takes the highest-value piece available" },
  { id: "stockfish-depth-1", name: "Stockfish Depth 1", desc: "Stockfish at depth 1 (fast)" },
  { id: "stockfish-depth-3", name: "Stockfish Depth 3", desc: "Stockfish at depth 3 (medium)" },
  { id: "stockfish-fast",    name: "Stockfish Fast",    desc: "Stockfish with tight time limit" },
  { id: "stockfish-slow",    name: "Stockfish Slow",    desc: "Stockfish with relaxed time limit" },
  { id: "searchess-ai-v1",   name: "Searchess AI v1",   desc: "Searchess neural network engine" },
];

const FORMATS: Array<{ value: PublicTournamentFormat; label: string }> = [
  { value: "swiss",             label: "Swiss" },
  { value: "league",            label: "League (round-robin)" },
  { value: "singleElimination", label: "Single Elimination" },
  { value: "doubleElimination", label: "Double Elimination" },
  { value: "groupStage",        label: "Group Stage" },
  { value: "randomKnockout",    label: "Random Knockout" },
];

interface FormState {
  name: string;
  nbRounds: string;
  clockLimitSeconds: string;
  clockIncrement: string;
  rated: boolean;
  format: PublicTournamentFormat;
  matchesPerPairing: string;
  openingKey: string;
}

function initialForm(): FormState {
  return {
    name: "",
    nbRounds: "5",
    clockLimitSeconds: "300",
    clockIncrement: "0",
    rated: true,
    format: "swiss",
    matchesPerPairing: "1",
    openingKey: "",
  };
}

function validate(f: FormState, selectedBotIds: Set<string>): string | null {
  if (selectedBotIds.size < 2)
    return "Select at least 2 Searchess bots — they play against each other.";
  if (!f.name.trim()) return "Tournament name is required.";
  const rounds = Number(f.nbRounds);
  if (!Number.isInteger(rounds) || rounds < 1 || rounds > 50)
    return "Number of rounds must be an integer between 1 and 50.";
  const limit = Number(f.clockLimitSeconds);
  if (!Number.isInteger(limit) || limit < 1 || limit > 10800)
    return "Clock limit must be 1–10800 seconds.";
  const inc = Number(f.clockIncrement);
  if (!Number.isInteger(inc) || inc < 0 || inc > 300)
    return "Clock increment must be 0–300 seconds.";
  const mpp = Number(f.matchesPerPairing);
  if (!Number.isInteger(mpp) || mpp < 1 || mpp > 10)
    return "Matches per pairing must be 1–10.";
  return null;
}

function toRequest(f: FormState, hostBot: OwnedTournamentBot): CreatePublicTournamentRequest {
  return {
    name: f.name.trim(),
    nbRounds: Number(f.nbRounds),
    clockLimit: Number(f.clockLimitSeconds),
    clockIncrement: Number(f.clockIncrement),
    rated: f.rated,
    format: f.format,
    matchesPerPairing: Number(f.matchesPerPairing),
    startPosition: f.openingKey || "standard",
    tournamentServerBotId:   hostBot.tournamentServerBotId,
    tournamentServerBotName: hostBot.tournamentServerBotName,
  };
}

export default function SearchessBotTournamentCreatePage() {
  const navigate = useNavigate();
  const [form, setForm]               = useState<FormState>(initialForm);
  const [submitting, setSubmitting]   = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [openings, setOpenings]       = useState<PublicOpening[]>([]);
  const [ownedBots, setOwnedBots]     = useState<OwnedTournamentBot[]>([]);
  const [botsLoading, setBotsLoading] = useState(true);
  const [selectedBotIds, setSelectedBotIds] = useState<Set<string>>(new Set());

  useEffect(() => {
    listPublicOpenings().then(setOpenings).catch(() => {});
  }, []);

  useEffect(() => {
    setBotsLoading(true);
    getMyTournamentBots()
      .then(setOwnedBots)
      .catch(() => {})
      .finally(() => setBotsLoading(false));
  }, []);

  function setField<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function toggleBot(id: string) {
    setSelectedBotIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  // Current flow: owned bots only (already registered on the Tournament Server).
  // TODO(Step 2): after catalog bot system-registration API is implemented, extend
  // this to:
  //   1. Register each selected catalog bot on the TS (POST /api/auth/register)
  //      and obtain a tournamentServerBotId.
  //   2. Record the searchessCatalogBotId mapping in user-service so the
  //      PublicTournamentBotRunner can look up and submit moves automatically.
  //   3. Join all selected bots (owned + catalog) in a single atomic sequence.
  //   4. Optionally auto-start the tournament once all bots are joined.
  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const err = validate(form, selectedBotIds);
    if (err) { setSubmitError(err); return; }

    const selectedOwned = ownedBots.filter((b) => selectedBotIds.has(b.searchessBotId));
    const hostBot = selectedOwned[0];
    if (!hostBot) { setSubmitError("Selected bot not found. Refresh and try again."); return; }

    const joinBots = selectedOwned.slice(1);

    setSubmitError(null);
    setSubmitting(true);

    let tournamentId: string;
    try {
      const created = await createPublicTournament(toRequest(form, hostBot));
      tournamentId = created.id;
      try {
        await recordTournamentHost(tournamentId, {
          directorTournamentServerBotId:   hostBot.tournamentServerBotId,
          directorTournamentServerBotName: hostBot.tournamentServerBotName,
        });
      } catch {
        setSubmitError(
          `Tournament created (ID: ${tournamentId}), but Searchess could not record the host. ` +
          `Please delete the tournament and try again.`
        );
        setSubmitting(false);
        return;
      }
      try {
        await recordTournamentParticipant(tournamentId, {
          tournamentServerBotId:   hostBot.tournamentServerBotId,
          tournamentServerBotName: hostBot.tournamentServerBotName,
        });
      } catch {
        // Non-critical: can be repaired from the detail page.
      }
    } catch (e: unknown) {
      setSubmitError(e instanceof Error ? e.message : "Failed to create tournament.");
      setSubmitting(false);
      return;
    }

    for (const bot of joinBots) {
      try {
        await joinPublicTournamentWithBot(tournamentId, {
          tournamentServerBotId:   bot.tournamentServerBotId,
          tournamentServerBotName: bot.tournamentServerBotName,
        });
        try {
          await recordTournamentParticipant(tournamentId, {
            tournamentServerBotId:   bot.tournamentServerBotId,
            tournamentServerBotName: bot.tournamentServerBotName,
          });
        } catch {
          // Non-critical.
        }
      } catch {
        // Remaining bots can be joined from the detail page if this fails.
      }
    }

    setSubmitting(false);
    navigate(`/play-tournament/${tournamentId}`);
  }

  const canSubmit = !submitting && !botsLoading && selectedBotIds.size >= 2;

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">

        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Searchess Bot Tournament</h1>
            <p className="play-tournament-subtitle">
              Controlled mode — Searchess manages all selected bots
            </p>
          </div>
          <Button variant="secondary" size="lg" onClick={() => navigate("/play-tournament")}>
            ← Back
          </Button>
        </div>

        {/* Mode rules */}
        <div className="pt-info-callout">
          <p className="pt-info-callout__line">
            Searchess controls all selected bots in this mode. No manual moves are needed.
          </p>
          <p className="pt-info-callout__line">
            External bots cannot join this controlled tournament unless they register independently on the shared Tournament Server.
          </p>
          <p className="pt-info-callout__line">
            For fully isolated tournaments with no external participants, a private Tournament Server instance would be required.
          </p>
        </div>

        {submitError && (
          <div style={{ marginBottom: 16 }}>
            <ErrorState message={submitError} />
          </div>
        )}

        <form
          className="play-tournament-form"
          onSubmit={(e) => { void handleSubmit(e); }}
        >

          {/* ── My Searchess bots ─────────────────────────────────────────── */}
          <div className="play-tournament-form-group">
            <label className="play-tournament-form-label">
              My Searchess bots <span className="play-tournament-required">*</span>
            </label>
            {botsLoading ? (
              <p style={{ fontSize: "0.875rem", color: "var(--text-secondary, #9090a8)", margin: 0 }}>
                Loading your bots…
              </p>
            ) : ownedBots.length === 0 ? (
              <p style={{ fontSize: "0.875rem", color: "var(--text-secondary, #9090a8)", margin: 0 }}>
                No Searchess bots registered yet. Use the Public Tournament tab to register a bot first,
                then return here.
              </p>
            ) : (
              <div className="qt-bot-checkboxes">
                {ownedBots.map((b) => (
                  <label
                    key={b.searchessBotId}
                    className={`qt-bot-checkbox-item${
                      selectedBotIds.has(b.searchessBotId) ? " qt-bot-checkbox-item--selected" : ""
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={selectedBotIds.has(b.searchessBotId)}
                      onChange={() => toggleBot(b.searchessBotId)}
                    />
                    <span className="qt-bot-checkbox-name">{b.tournamentServerBotName}</span>
                    <span className="qt-bot-checkbox-meta">Your bot · Searchess controlled</span>
                  </label>
                ))}
              </div>
            )}
            {selectedBotIds.size === 1 && (
              <p className="pt-bot-warning">
                Select at least 2 bots — they play against each other.
              </p>
            )}
            {selectedBotIds.size >= 2 && (
              <p style={{ fontSize: "0.78rem", color: "var(--text-secondary, #9090a8)", marginTop: 6 }}>
                {selectedBotIds.size} bots selected. First bot in your list is the tournament host.
              </p>
            )}
          </div>

          {/* ── Searchess catalog bots (Step 2 placeholder) ───────────────── */}
          {/* TODO(Step 2): enable selection once catalog bots have system-registration
              on the Tournament Server. See SEARCHESS_CATALOG_BOTS constant above. */}
          <div className="pt-catalog-section">
            <p className="play-tournament-form-label">
              Searchess catalog bots
              <span className="pt-coming-soon-badge">Step 2</span>
            </p>
            <div className="qt-bot-checkboxes qt-bot-checkboxes--foreign">
              {SEARCHESS_CATALOG_BOTS.map((bot) => (
                <div key={bot.id} className="qt-bot-checkbox-item qt-bot-checkbox-item--disabled">
                  <input type="checkbox" disabled />
                  <span className="qt-bot-checkbox-name" style={{ opacity: 0.55 }}>
                    {bot.name}
                  </span>
                  <span className="qt-bot-checkbox-meta">{bot.desc}</span>
                </div>
              ))}
            </div>
            <p style={{ fontSize: "0.78rem", color: "var(--text-secondary, #9090a8)", marginTop: 6 }}>
              Catalog bots require a system-registration step on the Tournament Server.
              Selectable in the next implementation phase.
            </p>
          </div>

          {/* ── Tournament name ───────────────────────────────────────────── */}
          <div className="play-tournament-form-group">
            <label className="play-tournament-form-label" htmlFor="sbt-name">
              Tournament name <span className="play-tournament-required">*</span>
            </label>
            <input
              id="sbt-name"
              className="play-tournament-form-input"
              type="text"
              value={form.name}
              maxLength={100}
              onChange={(e) => setField("name", e.target.value)}
              placeholder="e.g. Searchess Engine Showdown #1"
            />
          </div>

          {/* ── Numeric settings ──────────────────────────────────────────── */}
          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="sbt-rounds">
                Rounds <span className="play-tournament-required">*</span>
              </label>
              <input
                id="sbt-rounds"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number" min={1} max={50}
                value={form.nbRounds}
                onChange={(e) => setField("nbRounds", e.target.value)}
              />
            </div>
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="sbt-clock">
                Clock limit (s) <span className="play-tournament-required">*</span>
              </label>
              <input
                id="sbt-clock"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number" min={1} max={10800}
                value={form.clockLimitSeconds}
                onChange={(e) => setField("clockLimitSeconds", e.target.value)}
              />
            </div>
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="sbt-inc">
                Increment (s)
              </label>
              <input
                id="sbt-inc"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number" min={0} max={300}
                value={form.clockIncrement}
                onChange={(e) => setField("clockIncrement", e.target.value)}
              />
            </div>
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="sbt-mpp">
                Games/pairing
              </label>
              <input
                id="sbt-mpp"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number" min={1} max={10}
                value={form.matchesPerPairing}
                onChange={(e) => setField("matchesPerPairing", e.target.value)}
              />
            </div>
          </div>

          {/* ── Format + opening ──────────────────────────────────────────── */}
          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="sbt-format">Format</label>
              <select
                id="sbt-format"
                className="play-tournament-form-select"
                value={form.format}
                onChange={(e) => setField("format", e.target.value as PublicTournamentFormat)}
              >
                {FORMATS.map((f) => (
                  <option key={f.value} value={f.value}>{f.label}</option>
                ))}
              </select>
            </div>
            {openings.length > 0 && (
              <div className="play-tournament-form-group">
                <label className="play-tournament-form-label" htmlFor="sbt-opening">
                  Opening (optional)
                </label>
                <select
                  id="sbt-opening"
                  className="play-tournament-form-select"
                  value={form.openingKey}
                  onChange={(e) => setField("openingKey", e.target.value)}
                >
                  <option value="">Standard</option>
                  {openings.map((o) => (
                    <option key={o.key} value={o.key}>{o.name}</option>
                  ))}
                </select>
              </div>
            )}
          </div>

          {/* ── Rated ─────────────────────────────────────────────────────── */}
          <div className="play-tournament-form-group play-tournament-form-group--check">
            <input
              id="sbt-rated"
              type="checkbox"
              checked={form.rated}
              onChange={(e) => setField("rated", e.target.checked)}
            />
            <label htmlFor="sbt-rated" className="play-tournament-form-label">
              Rated tournament
            </label>
          </div>

          <div className="play-tournament-form-actions">
            <Button
              type="submit"
              variant="primary"
              size="lg"
              disabled={!canSubmit}
            >
              {submitting
                ? "Creating…"
                : selectedBotIds.size < 2
                ? "Select at least 2 bots to continue"
                : "Create Searchess Bot Tournament"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
