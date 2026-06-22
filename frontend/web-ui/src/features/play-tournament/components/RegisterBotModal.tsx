import { useEffect, useState } from "react";
import { registerPublicBot } from "../../../api/publicTournamentClient";
import { recordTournamentBotOwnership } from "../../../api/userServiceClient";
import { fetchTournamentBots } from "../../../api/tournamentClient";
import type { PublicRegisteredBot } from "../../../api/publicTournamentTypes";
import type { BotSummary } from "../../../api/tournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";

// Deterministic Tournament Server bot name for a Searchess catalog entry.
// Uses bot.botId (stable kebab-case slug) so the same Searchess bot always
// maps to the same Tournament Server name, enabling reliable "already owned" detection.
function toTournamentServerBotName(bot: BotSummary): string {
  const slug = bot.botId.trim().toLowerCase();
  return slug.startsWith("searchess-") ? slug : `searchess-${slug}`;
}

// ── Add Searchess bot ─────────────────────────────────────────────────────────

interface SearchessBotSectionProps {
  publicBots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  onRegistered: (bot: PublicRegisteredBot) => void;
}

function SearchessBotSection({ publicBots, ownedIds, onRegistered }: SearchessBotSectionProps) {
  const [catalog, setCatalog]         = useState<BotSummary[]>([]);
  const [loading, setLoading]         = useState(true);
  const [loadError, setLoadError]     = useState<string | null>(null);
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);
  const [regError, setRegError]       = useState<string | null>(null);

  useEffect(() => {
    fetchTournamentBots()
      .then(setCatalog)
      .catch((e: unknown) =>
        setLoadError(e instanceof Error ? e.message : "Failed to load Searchess bots.")
      )
      .finally(() => setLoading(false));
  }, []);

  async function handleRegister() {
    const bot = catalog.find((b) => b.botId === selectedId);
    if (!bot) return;
    setRegistering(true);
    setRegError(null);
    try {
      const publicBot = await registerPublicBot({
        name:         toTournamentServerBotName(bot),
        family:       bot.family || undefined,
        strategyType: bot.strategyType || undefined,
        engineType:   bot.engineType || undefined,
        modelVersion: bot.modelVersion || undefined,
      });
      await recordTournamentBotOwnership({ botId: publicBot.id, botName: publicBot.name });
      onRegistered(publicBot);
    } catch (e: unknown) {
      setRegError(e instanceof Error ? e.message : "Registration failed.");
      setRegistering(false);
    }
  }

  // Names of Tournament Server bots already owned by this user — used to detect
  // Searchess catalog entries that were already provisioned (matched by display name).
  const ownedNames = new Set(
    publicBots.filter((b) => ownedIds.has(b.id)).map((b) => b.name)
  );

  return (
    <div>
      <p className="rbm-hint">
        Select a Searchess bot to provision it on the public Tournament Server and add it to your account.
        Unavailable bots require additional services (e.g. Stockfish binary, Searchess AI) to be running.
      </p>

      {loading && <p className="rbm-empty">Loading Searchess bots…</p>}
      {loadError && <ErrorState message={loadError} />}

      {!loading && !loadError && (
        <>
          <div className="rbm-existing-list" role="listbox" aria-label="Searchess bots">
            {catalog.length === 0 ? (
              <p className="rbm-empty">No Searchess bots found on this server.</p>
            ) : catalog.map((b) => {
              const isSelected     = selectedId === b.botId;
              const isUnavailable  = !b.available;
              const isAlreadyOwned = ownedNames.has(toTournamentServerBotName(b));
              const isDisabled     = isUnavailable || isAlreadyOwned;
              const parts          = [b.family, b.strategyType, b.engineType, b.modelVersion]
                .filter((s) => s.length > 0 && s !== "none")
                .join(" · ");
              return (
                <div
                  key={b.botId}
                  role="option"
                  aria-selected={isSelected}
                  aria-disabled={isDisabled}
                  className={[
                    "rbm-bot-row",
                    isSelected && !isDisabled ? "rbm-bot-row--selected" : "",
                    isDisabled ? "rbm-bot-row--disabled" : "",
                  ].filter(Boolean).join(" ")}
                  onClick={() => { if (!isDisabled) setSelectedId(b.botId); }}
                >
                  <span className="rbm-bot-name">{b.displayName}</span>
                  {parts && <span className="rbm-bot-meta">{parts}</span>}
                  {isAlreadyOwned ? (
                    <span className="pt-bot-badge pt-bot-badge--own">Already yours</span>
                  ) : isUnavailable ? (
                    <span
                      className="pt-bot-badge pt-bot-badge--unavailable"
                      title={b.unavailableReason ?? ""}
                    >
                      Unavailable
                    </span>
                  ) : (
                    <span className="pt-bot-badge pt-bot-badge--searchess">Searchess</span>
                  )}
                </div>
              );
            })}
          </div>

          {regError && <ErrorState message={regError} />}

          <div className="pt-modal-actions" style={{ justifyContent: "flex-start", marginTop: 12 }}>
            <Button
              variant="primary"
              size="sm"
              disabled={!selectedId || registering}
              onClick={() => { void handleRegister(); }}
            >
              {registering ? "Registering…" : "Register on Tournament Server"}
            </Button>
          </div>
        </>
      )}

      {publicBots.length > 0 && (
        <div className="rbm-public-note">
          <p className="pt-bot-section-label">Public server bots</p>
          <p className="rbm-hint" style={{ marginBottom: 0 }}>
            {publicBots.length} bot{publicBots.length !== 1 ? "s" : ""} from all teams are registered on
            the Tournament Server
            {ownedIds.size > 0 ? ` (${ownedIds.size} yours)` : ""}.
            These are managed by their respective teams and cannot be added to My Bots here.
          </p>
        </div>
      )}
    </div>
  );
}

// ── Create custom bot ─────────────────────────────────────────────────────────

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
  const [form, setForm]             = useState<FormState>(initialForm);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError]           = useState<string | null>(null);

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
        name:         form.name.trim(),
        family:       form.family.trim() || undefined,
        strategyType: form.strategyType.trim() || undefined,
        engineType:   form.engineType.trim() || undefined,
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
        Create a new custom tournament bot entry directly on the public Tournament Server and record ownership
        in your Searchess account. Use this for custom or experimental bots not in the Searchess catalog.
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
            placeholder="e.g. MyCustomBot"
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

type ModalSection = "searchess" | "create";

interface Props {
  publicBots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  onClose: () => void;
  onRegistered: (bot: PublicRegisteredBot) => void;
}

export default function RegisterBotModal({ publicBots, ownedIds, onClose, onRegistered }: Props) {
  const [section, setSection] = useState<ModalSection>("searchess");

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
            className={`rbm-tab${section === "searchess" ? " rbm-tab--active" : ""}`}
            onClick={() => setSection("searchess")}
          >
            Add Searchess bot
          </button>
          <button
            type="button"
            className={`rbm-tab${section === "create" ? " rbm-tab--active" : ""}`}
            onClick={() => setSection("create")}
          >
            Create custom bot
          </button>
        </div>

        {section === "searchess" && (
          <SearchessBotSection publicBots={publicBots} ownedIds={ownedIds} onRegistered={onRegistered} />
        )}
        {section === "create" && (
          <CreateBotSection onCreated={onRegistered} onClose={onClose} />
        )}
      </div>
    </div>
  );
}
