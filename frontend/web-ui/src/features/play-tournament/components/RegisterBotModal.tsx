import { useState } from "react";
import { registerPublicBot } from "../../../api/publicTournamentClient";
import { recordTournamentBotOwnership } from "../../../api/userServiceClient";
import type { PublicRegisteredBot } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";

interface FormState {
  name: string;
  family: string;
  strategyType: string;
  engineType: string;
  modelVersion: string;
}

function initialForm(): FormState {
  return { name: "", family: "searchess", strategyType: "", engineType: "searchess", modelVersion: "" };
}

interface Props {
  onClose: () => void;
  onRegistered: (bot: PublicRegisteredBot) => void;
}

export default function RegisterBotModal({ onClose, onRegistered }: Props) {
  const [form, setForm] = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function set<K extends keyof FormState>(key: K, value: string) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.name.trim()) { setError("Bot name is required."); return; }
    setError(null);
    setSubmitting(true);
    try {
      const bot = await registerPublicBot({
        name: form.name.trim(),
        family: form.family.trim() || undefined,
        strategyType: form.strategyType.trim() || undefined,
        engineType: form.engineType.trim() || undefined,
        modelVersion: form.modelVersion.trim() || undefined,
      });
      await recordTournamentBotOwnership({ botId: bot.id, botName: bot.name });
      onRegistered(bot);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to register bot.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="pt-modal-overlay" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="pt-modal-box" role="dialog" aria-modal="true" aria-label="Register your bot">
        <div className="pt-modal-header">
          <h2 className="pt-modal-title">Register Your Bot</h2>
          <button type="button" className="pt-modal-close" onClick={onClose} aria-label="Close">✕</button>
        </div>

        {error && <ErrorState message={error} />}

        <form onSubmit={(e) => { void handleSubmit(e); }} className="pt-modal-form">

          <div className="play-tournament-form-group">
            <label className="play-tournament-form-label" htmlFor="rb-name">
              Bot name <span className="play-tournament-required">*</span>
            </label>
            <input
              id="rb-name"
              className="play-tournament-form-input"
              type="text"
              value={form.name}
              maxLength={80}
              onChange={(e) => set("name", e.target.value)}
              placeholder="e.g. SearchessAlpha"
              required
            />
          </div>

          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="rb-family">Family</label>
              <input
                id="rb-family"
                className="play-tournament-form-input"
                type="text"
                value={form.family}
                maxLength={50}
                onChange={(e) => set("family", e.target.value)}
                placeholder="searchess"
              />
            </div>

            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="rb-engine">Engine type</label>
              <input
                id="rb-engine"
                className="play-tournament-form-input"
                type="text"
                value={form.engineType}
                maxLength={50}
                onChange={(e) => set("engineType", e.target.value)}
                placeholder="searchess"
              />
            </div>
          </div>

          <div className="play-tournament-form-row">
            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="rb-strategy">Strategy type</label>
              <input
                id="rb-strategy"
                className="play-tournament-form-input"
                type="text"
                value={form.strategyType}
                maxLength={50}
                onChange={(e) => set("strategyType", e.target.value)}
                placeholder="e.g. manual-test"
              />
            </div>

            <div className="play-tournament-form-group">
              <label className="play-tournament-form-label" htmlFor="rb-version">Model version</label>
              <input
                id="rb-version"
                className="play-tournament-form-input"
                type="text"
                value={form.modelVersion}
                maxLength={30}
                onChange={(e) => set("modelVersion", e.target.value)}
                placeholder="e.g. phase1"
              />
            </div>
          </div>

          <div className="pt-modal-actions">
            <Button type="submit" variant="primary" size="sm" disabled={submitting}>
              {submitting ? "Registering…" : "Register bot"}
            </Button>
            <Button type="button" variant="secondary" size="sm" onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
