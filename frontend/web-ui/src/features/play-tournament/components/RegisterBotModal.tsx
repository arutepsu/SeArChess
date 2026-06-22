import { useState } from "react";
import { registerPublicBot } from "../../../api/publicTournamentClient";
import { recordTournamentBotOwnership } from "../../../api/userServiceClient";
import type { PublicRegisteredBot } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";

// ── Choose existing ───────────────────────────────────────────────────────────

interface ExistingBotSectionProps {
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  onAdded: (bot: PublicRegisteredBot) => void;
}

function ExistingBotSection({ bots, ownedIds, onAdded }: ExistingBotSectionProps) {
  const [search, setSearch]     = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [adding, setAdding]     = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const trimmed = search.trim().toLowerCase();
  const filtered = bots.filter((b) =>
    !trimmed ||
    b.name.toLowerCase().includes(trimmed) ||
    (b.family ?? "").toLowerCase().includes(trimmed) ||
    (b.strategyType ?? "").toLowerCase().includes(trimmed) ||
    (b.engineType ?? "").toLowerCase().includes(trimmed)
  );

  const sorted = [...filtered].sort((a, b) => {
    const aO = ownedIds.has(a.id);
    const bO = ownedIds.has(b.id);
    if (aO === bO) return a.name.localeCompare(b.name);
    return aO ? 1 : -1;
  });

  async function handleAdd() {
    const bot = bots.find((b) => b.id === selectedId);
    if (!bot) return;
    setAdding(true);
    setError(null);
    try {
      await recordTournamentBotOwnership({ botId: bot.id, botName: bot.name });
      onAdded(bot);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to add bot.");
      setAdding(false);
    }
  }

  return (
    <div>
      <p className="rbm-hint">
        Pick any bot already registered on the public tournament server to add it to your Searchess account.
        This only records local ownership — it does not transfer server-side permissions.
      </p>
      {bots.length === 0 ? (
        <p className="rbm-empty">No bots found on the public server.</p>
      ) : (
        <>
          <input
            className="rbm-search"
            type="text"
            placeholder="Search by name, family, engine…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            autoComplete="off"
          />
          <div className="rbm-existing-list" role="listbox" aria-label="Public bots">
            {sorted.length === 0 ? (
              <p className="rbm-empty">No bots match "{search}".</p>
            ) : sorted.map((b) => {
              const isOwned    = ownedIds.has(b.id);
              const isSelected = selectedId === b.id;
              const metaParts  = [b.family, b.strategyType, b.engineType, b.modelVersion].filter(Boolean);
              return (
                <div
                  key={b.id}
                  role="option"
                  aria-selected={isSelected}
                  aria-disabled={isOwned}
                  className={[
                    "rbm-bot-row",
                    isSelected && !isOwned ? "rbm-bot-row--selected" : "",
                    isOwned ? "rbm-bot-row--disabled" : "",
                  ].filter(Boolean).join(" ")}
                  onClick={() => { if (!isOwned) setSelectedId(b.id); }}
                >
                  <span className="rbm-bot-name">{b.name}</span>
                  {metaParts.length > 0 && (
                    <span className="rbm-bot-meta">{metaParts.join(" · ")}</span>
                  )}
                  <span className={`pt-bot-badge ${isOwned ? "pt-bot-badge--own" : "pt-bot-badge--foreign"}`}>
                    {isOwned ? "Already yours" : "Public"}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}
      {error && <ErrorState message={error} />}
      <div className="pt-modal-actions" style={{ justifyContent: "flex-start", marginTop: 12 }}>
        <Button
          variant="primary"
          size="sm"
          disabled={!selectedId || adding}
          onClick={() => { void handleAdd(); }}
        >
          {adding ? "Adding…" : "Add to My Bots"}
        </Button>
      </div>
    </div>
  );
}

// ── Create new ────────────────────────────────────────────────────────────────

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

interface CreateBotSectionProps {
  onCreated: (bot: PublicRegisteredBot) => void;
  onClose: () => void;
}

function CreateBotSection({ onCreated, onClose }: CreateBotSectionProps) {
  const [form, setForm]         = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError]       = useState<string | null>(null);

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
      onCreated(bot);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to register bot.");
      setSubmitting(false);
    }
  }

  return (
    <div>
      <p className="rbm-hint">
        Register a brand-new bot with the public tournament server and add it to your account in one step.
      </p>
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
  );
}

// ── Modal shell ───────────────────────────────────────────────────────────────

type ModalSection = "existing" | "create";

interface Props {
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  onClose: () => void;
  onRegistered: (bot: PublicRegisteredBot) => void;
}

export default function RegisterBotModal({ bots, ownedIds, onClose, onRegistered }: Props) {
  const [section, setSection] = useState<ModalSection>("existing");

  return (
    <div className="pt-modal-overlay" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="pt-modal-box" role="dialog" aria-modal="true" aria-label="Add Bot to My Bots">
        <div className="pt-modal-header">
          <h2 className="pt-modal-title">Add Bot to My Bots</h2>
          <button type="button" className="pt-modal-close" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <div className="rbm-tabs">
          <button
            type="button"
            className={`rbm-tab${section === "existing" ? " rbm-tab--active" : ""}`}
            onClick={() => setSection("existing")}
          >
            Choose existing
          </button>
          <button
            type="button"
            className={`rbm-tab${section === "create" ? " rbm-tab--active" : ""}`}
            onClick={() => setSection("create")}
          >
            Create new bot
          </button>
        </div>

        {section === "existing" && (
          <ExistingBotSection bots={bots} ownedIds={ownedIds} onAdded={onRegistered} />
        )}
        {section === "create" && (
          <CreateBotSection onCreated={onRegistered} onClose={onClose} />
        )}
      </div>
    </div>
  );
}
