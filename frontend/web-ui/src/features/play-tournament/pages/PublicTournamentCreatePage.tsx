import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { createPublicTournament } from "../../../api/publicTournamentClient";
import type { CreatePublicTournamentRequest, PublicTournamentFormat } from "../../../api/publicTournamentTypes";
import { listPublicOpenings } from "../../../api/publicTournamentClient";
import type { PublicOpening } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import "./PlayTournament.css";

const FORMATS: Array<{ value: PublicTournamentFormat; label: string }> = [
  { value: "swiss", label: "Swiss" },
  { value: "league", label: "League (round-robin)" },
  { value: "singleElimination", label: "Single Elimination" },
  { value: "doubleElimination", label: "Double Elimination" },
  { value: "groupStage", label: "Group Stage" },
  { value: "randomKnockout", label: "Random Knockout" },
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

function validate(f: FormState): string | null {
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

function toRequest(f: FormState): CreatePublicTournamentRequest {
  const req: CreatePublicTournamentRequest = {
    name: f.name.trim(),
    nbRounds: Number(f.nbRounds),
    clockLimit: Number(f.clockLimitSeconds),
    clockIncrement: Number(f.clockIncrement),
    rated: f.rated,
    format: f.format,
    matchesPerPairing: Number(f.matchesPerPairing),
    startPosition: f.openingKey || "standard",
  };
  return req;
}

export default function PublicTournamentCreatePage() {
  const navigate = useNavigate();
  const [form, setForm] = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [openings, setOpenings] = useState<PublicOpening[]>([]);

  useEffect(() => {
    listPublicOpenings()
      .then(setOpenings)
      .catch(() => { /* openings are optional */ });
  }, []);

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const err = validate(form);
    if (err) { setSubmitError(err); return; }
    setSubmitError(null);
    setSubmitting(true);
    try {
      const created = await createPublicTournament(toRequest(form));
      navigate(`/play-tournament/${created.id}`);
    } catch (e: unknown) {
      setSubmitError(e instanceof Error ? e.message : "Failed to create tournament.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Create Public Tournament</h1>
            <p className="play-tournament-subtitle">Tournament Server — director action</p>
          </div>
          <Button variant="secondary" size="lg" onClick={() => navigate("/play-tournament")}>
            ← Cancel
          </Button>
        </div>

        {submitError && <ErrorState message={submitError} />}

        <form className="play-tournament-form" onSubmit={(e) => { void handleSubmit(e); }}>

          <div className="play-tournament-form-group">
            <label className="play-tournament-form-label" htmlFor="pt-name">
              Tournament name <span className="play-tournament-required">*</span>
            </label>
            <input
              id="pt-name"
              className="play-tournament-form-input"
              type="text"
              value={form.name}
              maxLength={100}
              onChange={(e) => set("name", e.target.value)}
              placeholder="e.g. Sunday Bot League #3"
              required
            />
          </div>

          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="pt-rounds">
                Rounds <span className="play-tournament-required">*</span>
              </label>
              <input
                id="pt-rounds"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number"
                min={1}
                max={50}
                value={form.nbRounds}
                onChange={(e) => set("nbRounds", e.target.value)}
              />
            </div>

            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="pt-clock">
                Clock limit (s) <span className="play-tournament-required">*</span>
              </label>
              <input
                id="pt-clock"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number"
                min={1}
                max={10800}
                value={form.clockLimitSeconds}
                onChange={(e) => set("clockLimitSeconds", e.target.value)}
              />
            </div>

            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="pt-inc">
                Increment (s)
              </label>
              <input
                id="pt-inc"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number"
                min={0}
                max={300}
                value={form.clockIncrement}
                onChange={(e) => set("clockIncrement", e.target.value)}
              />
            </div>

            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="pt-mpp">
                Games/pairing
              </label>
              <input
                id="pt-mpp"
                className="play-tournament-form-input play-tournament-form-input--narrow"
                type="number"
                min={1}
                max={10}
                value={form.matchesPerPairing}
                onChange={(e) => set("matchesPerPairing", e.target.value)}
              />
            </div>
          </div>

          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="pt-format">
                Format
              </label>
              <select
                id="pt-format"
                className="play-tournament-form-select"
                value={form.format}
                onChange={(e) => set("format", e.target.value as PublicTournamentFormat)}
              >
                {FORMATS.map((f) => (
                  <option key={f.value} value={f.value}>{f.label}</option>
                ))}
              </select>
            </div>

            {openings.length > 0 && (
              <div className="play-tournament-form-group">
                <label className="play-tournament-form-label" htmlFor="pt-opening">
                  Opening (optional)
                </label>
                <select
                  id="pt-opening"
                  className="play-tournament-form-select"
                  value={form.openingKey}
                  onChange={(e) => set("openingKey", e.target.value)}
                >
                  <option value="">Standard</option>
                  {openings.map((o) => (
                    <option key={o.key} value={o.key}>{o.name}</option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <div className="play-tournament-form-group play-tournament-form-group--check">
            <input
              id="pt-rated"
              type="checkbox"
              checked={form.rated}
              onChange={(e) => set("rated", e.target.checked)}
            />
            <label htmlFor="pt-rated" className="play-tournament-form-label">
              Rated tournament
            </label>
          </div>

          <div className="play-tournament-form-actions">
            <Button type="submit" variant="primary" size="lg" disabled={submitting}>
              {submitting ? "Creating…" : "Create Tournament"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
