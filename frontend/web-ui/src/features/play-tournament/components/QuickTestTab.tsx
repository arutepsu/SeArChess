import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  addPublicTournamentParticipant,
  createPublicTournament,
  startPublicTournament,
} from "../../../api/publicTournamentClient";
import { recordTournamentParticipant } from "../../../api/userServiceClient";
import type { PublicRegisteredBot, PublicTournamentFormat } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";

// ── Constants ─────────────────────────────────────────────────────────────────

const CLOCK_OPTIONS: Array<{ label: string; value: number }> = [
  { label: "30 s",  value: 30  },
  { label: "1 min", value: 60  },
  { label: "2 min", value: 120 },
  { label: "5 min", value: 300 },
];

const FORMATS: Array<{ value: PublicTournamentFormat; label: string }> = [
  { value: "swiss",              label: "Swiss" },
  { value: "league",             label: "League (round-robin)" },
  { value: "singleElimination",  label: "Single Elimination" },
  { value: "doubleElimination",  label: "Double Elimination" },
  { value: "groupStage",         label: "Group Stage" },
  { value: "randomKnockout",     label: "Random Knockout" },
];

// ── Types ─────────────────────────────────────────────────────────────────────

interface PartialSuccess {
  tournamentId: string;
  message: string;
}

interface Props {
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  canManage: boolean;
  onRegisterClick: () => void;
  onSwitchToMyBots: () => void;
  onTournamentCreated: () => void;
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function QuickTestTab({
  bots,
  ownedIds,
  canManage,
  onRegisterClick,
  onSwitchToMyBots,
  onTournamentCreated,
}: Props) {
  const navigate = useNavigate();

  const owned   = bots.filter((b) => ownedIds.has(b.id));
  const foreign = bots.filter((b) => !ownedIds.has(b.id));

  const [selected,      setSelected]      = useState<Set<string>>(new Set());
  const [name,          setName]          = useState("Searchess Quick Test");
  const [clockLimit,    setClockLimit]    = useState(30);
  const [format,        setFormat]        = useState<PublicTournamentFormat>("swiss");
  const [rated,         setRated]         = useState(false);
  const [submitting,    setSubmitting]    = useState(false);
  const [submitError,   setSubmitError]   = useState<string | null>(null);
  const [partialSuccess, setPartialSuccess] = useState<PartialSuccess | null>(null);
  const [submitPhase,   setSubmitPhase]   = useState<"create" | "addBots" | "start" | null>(null);

  function toggleBot(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function selectAll() { setSelected(new Set(owned.map((b) => b.id))); }
  function clearAll()  { setSelected(new Set()); }

  async function handleCreateAndStart() {
    setSubmitting(true);
    setSubmitError(null);
    setPartialSuccess(null);

    // Phase 1: create tournament
    setSubmitPhase("create");
    let tournamentId: string;
    try {
      const created = await createPublicTournament({
        name: name.trim() || "Searchess Quick Test",
        nbRounds: 1,
        clockLimit,
        clockIncrement: 0,
        rated,
        format,
        startPosition: "standard",
      });
      tournamentId = created.id;
    } catch (e: unknown) {
      setSubmitError(e instanceof Error ? e.message : "Failed to create tournament.");
      setSubmitting(false);
      setSubmitPhase(null);
      return;
    }

    // Phase 2: add selected bots as participants (director path) and record in Searchess registry
    setSubmitPhase("addBots");
    for (const botId of Array.from(selected)) {
      try {
        await addPublicTournamentParticipant(tournamentId, botId);
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : "Unknown error";
        setPartialSuccess({
          tournamentId,
          message: `Tournament created but failed while adding a participant: ${msg}. Open the tournament to add bots manually, then start it.`,
        });
        setSubmitting(false);
        setSubmitPhase(null);
        return;
      }
      // Best-effort: record in Searchess participant registry for the waiting lobby.
      const botName = bots.find((b) => b.id === botId)?.name ?? "";
      if (botName) {
        try {
          await recordTournamentParticipant(tournamentId, { tournamentServerBotId: botId, tournamentServerBotName: botName });
        } catch {
          // Non-critical: don't abort QuickTest flow if registry fails.
        }
      }
    }

    // Phase 3: start tournament
    setSubmitPhase("start");
    try {
      await startPublicTournament(tournamentId);
      onTournamentCreated();
      navigate(`/play-tournament/${tournamentId}`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Unknown error";
      setPartialSuccess({
        tournamentId,
        message: `Tournament created and all bots added, but start failed: ${msg}. Open the tournament to start it manually.`,
      });
    }

    setSubmitting(false);
    setSubmitPhase(null);
  }

  // ── Not authenticated ──────────────────────────────────────────────────────

  if (!canManage) {
    return (
      <div className="pt-placeholder">
        <p className="pt-placeholder-title">Quick Test</p>
        <p className="pt-placeholder-body">Sign in to create a quick test tournament.</p>
      </div>
    );
  }

  // ── Not enough owned bots ──────────────────────────────────────────────────

  if (owned.length < 2) {
    return (
      <div className="qt-need-bots">
        <p className="qt-need-bots-title">At least 2 registered bots required</p>
        <p className="qt-need-bots-body">
          Quick Test creates a one-round tournament using only your own bots. You currently have{" "}
          {owned.length === 0 ? "no bots" : "1 bot"} registered on this server.
          Register {owned.length === 0 ? "at least two" : "one more"} to continue.
        </p>
        <div className="qt-need-bots-actions">
          <Button variant="primary" size="sm" onClick={onRegisterClick}>
            + Register a bot
          </Button>
          {owned.length > 0 && (
            <Button variant="secondary" size="sm" onClick={onSwitchToMyBots}>
              My Bots
            </Button>
          )}
        </div>
      </div>
    );
  }

  // ── Main panel ─────────────────────────────────────────────────────────────

  const canSubmit = selected.size >= 2 && !submitting;
  const phaseLabel =
    submitPhase === "create"  ? "Creating tournament…" :
    submitPhase === "addBots" ? "Adding participants…" :
    submitPhase === "start"   ? "Starting tournament…" :
    "Create and Start Test";

  return (
    <div className="qt-panel">
      <p className="qt-description">
        <strong>Quick Test</strong> creates a one-round tournament using only your registered bots.
        Select two or more bots below, choose settings, then hit{" "}
        <em>Create and Start Test</em>.
      </p>

      {submitError && <ErrorState message={submitError} />}

      {partialSuccess && (
        <div className="qt-partial-success">
          <p className="qt-partial-success-title">Partially completed</p>
          <p className="qt-partial-success-body">{partialSuccess.message}</p>
          <Button
            variant="primary"
            size="sm"
            onClick={() => navigate(`/play-tournament/${partialSuccess.tournamentId}`)}
          >
            Open tournament
          </Button>
        </div>
      )}

      {/* ── Settings ─────────────────────────────────────────────────────── */}
      <div className="qt-settings">
        <div className="play-tournament-form-group qt-settings-name">
          <label className="play-tournament-form-label" htmlFor="qt-name">
            Tournament name
          </label>
          <input
            id="qt-name"
            className="play-tournament-form-input"
            type="text"
            value={name}
            maxLength={80}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="play-tournament-form-group">
          <label className="play-tournament-form-label" htmlFor="qt-clock">
            Clock
          </label>
          <select
            id="qt-clock"
            className="play-tournament-form-select"
            value={clockLimit}
            onChange={(e) => setClockLimit(Number(e.target.value))}
          >
            {CLOCK_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>

        <div className="play-tournament-form-group">
          <label className="play-tournament-form-label" htmlFor="qt-format">
            Format
          </label>
          <select
            id="qt-format"
            className="play-tournament-form-select"
            value={format}
            onChange={(e) => setFormat(e.target.value as PublicTournamentFormat)}
          >
            {FORMATS.map((f) => (
              <option key={f.value} value={f.value}>{f.label}</option>
            ))}
          </select>
        </div>

        <div className="qt-settings-fixed">
          <span className="qt-fixed-badge">1 round</span>
          <span className="qt-fixed-badge">Standard opening</span>
        </div>

        <div className="play-tournament-form-group play-tournament-form-group--check qt-settings-rated">
          <input
            id="qt-rated"
            type="checkbox"
            checked={rated}
            onChange={(e) => setRated(e.target.checked)}
          />
          <label htmlFor="qt-rated" className="play-tournament-form-label">
            Rated
          </label>
        </div>
      </div>

      {/* ── Bot selector ──────────────────────────────────────────────────── */}
      <div className="qt-bot-section">
        <div className="qt-bot-section-header">
          <span className="pt-bot-section-label">
            Your bots — select 2 or more ({selected.size} selected)
          </span>
          <div style={{ display: "flex", gap: 12 }}>
            <button type="button" className="pt-inline-link" onClick={selectAll}>
              Select all
            </button>
            <button type="button" className="pt-inline-link" onClick={clearAll}>
              Clear
            </button>
          </div>
        </div>

        <div className="qt-bot-checkboxes">
          {owned.map((b) => (
            <label
              key={b.id}
              className={`qt-bot-checkbox-item${selected.has(b.id) ? " qt-bot-checkbox-item--selected" : ""}`}
            >
              <input
                type="checkbox"
                checked={selected.has(b.id)}
                onChange={() => toggleBot(b.id)}
              />
              <span className="qt-bot-checkbox-name">{b.name}</span>
              {(b.family || b.engineType) && (
                <span className="qt-bot-checkbox-meta">
                  {[b.family, b.engineType].filter(Boolean).join(" · ")}
                </span>
              )}
              <span className="pt-bot-badge pt-bot-badge--own">Your bot</span>
            </label>
          ))}
        </div>

        {foreign.length > 0 && (
          <div className="qt-foreign-bots">
            <span className="pt-bot-section-label">
              Other teams — not selectable ({foreign.length})
            </span>
            <div className="qt-bot-checkboxes qt-bot-checkboxes--foreign">
              {foreign.map((b) => (
                <label key={b.id} className="qt-bot-checkbox-item qt-bot-checkbox-item--disabled">
                  <input type="checkbox" disabled checked={false} readOnly />
                  <span className="qt-bot-checkbox-name">{b.name}</span>
                  {b.family && <span className="qt-bot-checkbox-meta">{b.family}</span>}
                  <span className="pt-bot-badge pt-bot-badge--foreign">Public</span>
                </label>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* ── Submit ────────────────────────────────────────────────────────── */}
      <div className="qt-actions">
        <Button
          variant="primary"
          size="lg"
          disabled={!canSubmit}
          onClick={() => { void handleCreateAndStart(); }}
        >
          {submitting ? phaseLabel : "Create and Start Test"}
        </Button>
        {!submitting && selected.size < 2 && (
          <span className="qt-actions-hint">
            Select at least {2 - selected.size} more bot{2 - selected.size !== 1 ? "s" : ""} to continue
          </span>
        )}
      </div>
    </div>
  );
}
