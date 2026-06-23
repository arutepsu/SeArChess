import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listPublicBots, listPublicTournaments } from "../../../api/publicTournamentClient";
import { getMyTournamentBots } from "../../../api/userServiceClient";
import type { PublicAnalyticsExport, PublicRegisteredBot, PublicTournamentInfo, PublicTournamentListResponse, TournamentGameRow } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import { canManagePublicTournaments } from "../utils/publicTournamentAuth";
import RegisterBotModal from "../components/RegisterBotModal";
import QuickTestTab from "../components/QuickTestTab";
import GamesTab from "../components/GamesTab";
import AnalyticsTab from "../components/AnalyticsTab";
import { loadTournamentGames } from "../utils/gameLoader";
import "./PlayTournament.css";

// ── Types ─────────────────────────────────────────────────────────────────────

type TabId = "tournaments" | "myBots" | "quickTest" | "allGames" | "myGames" | "analytics";

const TABS: Array<{ id: TabId; label: string }> = [
  { id: "tournaments", label: "Public Tournaments" },
  { id: "myBots",      label: "My Bots" },
  { id: "quickTest",   label: "Quick Test" },
  { id: "allGames",    label: "All Games" },
  { id: "myGames",     label: "My Games" },
  { id: "analytics",   label: "Analytics" },
];

// ── Sub-components ────────────────────────────────────────────────────────────

function formatClock(limitSec: number, increment: number): string {
  const min = Math.floor(limitSec / 60);
  const sec = limitSec % 60;
  const base = sec === 0 ? `${min}m` : `${min}m${sec}s`;
  return increment > 0 ? `${base}+${increment}s` : base;
}

function TournamentCard({ t, onClick }: { t: PublicTournamentInfo; onClick: () => void }) {
  return (
    <button type="button" className="play-tournament-card" onClick={onClick}>
      <p className="play-tournament-card-name">{t.fullName}</p>
      <p className="play-tournament-card-meta">
        <span>{t.format}</span>
        <span>{formatClock(t.clock.limit, t.clock.increment)}</span>
        <span>{t.nbRounds} rounds</span>
        <span>{t.nbPlayers} bots</span>
        {t.rated && <span>Rated</span>}
      </p>
    </button>
  );
}

function TournamentGroup({
  label,
  statusClass,
  items,
  onSelect,
}: {
  label: string;
  statusClass: string;
  items: PublicTournamentInfo[];
  onSelect: (id: string) => void;
}) {
  if (items.length === 0) return null;
  return (
    <div className="play-tournament-group">
      <h2 className="play-tournament-group-title">
        <span className={`play-tournament-badge play-tournament-badge--${statusClass}`}>
          {label}
        </span>
      </h2>
      <div className="play-tournament-grid">
        {items.map((t) => (
          <TournamentCard key={t.id} t={t} onClick={() => onSelect(t.id)} />
        ))}
      </div>
    </div>
  );
}

function TournamentsTab({
  data,
  loading,
  error,
  canManage,
  onCreateClick,
  onSelect,
}: {
  data: PublicTournamentListResponse | null;
  loading: boolean;
  error: string | null;
  canManage: boolean;
  onCreateClick: () => void;
  onSelect: (id: string) => void;
}) {
  if (loading) return <LoadingState message="Loading tournaments…" />;
  if (error) return <ErrorState message={error} />;
  if (!data) return null;

  const total = data.created.length + data.started.length + data.finished.length;

  return (
    <>
      <TournamentGroup label="In progress"    statusClass="started"  items={data.started}  onSelect={onSelect} />
      <TournamentGroup label="Waiting to start" statusClass="created" items={data.created}  onSelect={onSelect} />
      <TournamentGroup label="Finished"       statusClass="finished" items={data.finished} onSelect={onSelect} />
      {total === 0 && (
        <div className="pt-placeholder">
          <p className="pt-placeholder-title">No tournaments yet</p>
          {canManage ? (
            <>
              <p className="pt-placeholder-body">Create one to get started.</p>
              <div style={{ marginTop: 12 }}>
                <Button variant="primary" size="sm" onClick={onCreateClick}>
                  + Create Tournament
                </Button>
              </div>
            </>
          ) : (
            <p className="pt-placeholder-body">Sign in to create a tournament.</p>
          )}
        </div>
      )}
    </>
  );
}

function MyBotsTab({
  bots,
  ownedIds,
  canRegister,
  onRegisterClick,
}: {
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  canRegister: boolean;
  onRegisterClick: () => void;
}) {
  const owned   = bots.filter((b) => ownedIds.has(b.id));
  const foreign = bots.filter((b) => !ownedIds.has(b.id));

  return (
    <div>
      {canRegister && owned.length === 0 && (
        <div className="pt-bots-empty-cta">
          <p className="pt-bots-empty-text">
            You have no Searchess bots on the Tournament Server yet.
            Register a Searchess bot to participate in tournaments.
          </p>
          <Button variant="primary" size="sm" onClick={onRegisterClick}>
            + Add Searchess bot
          </Button>
        </div>
      )}

      {owned.length > 0 && (
        <div className="pt-bot-list">
          <p className="pt-bot-section-label">Your bots</p>
          {owned.map((b) => (
            <div key={b.id} className="pt-bot-item pt-bot-item--owned">
              <span className="pt-bot-name">{b.name}</span>
              <span className="pt-bot-meta">
                {[b.family, b.engineType, b.modelVersion].filter(Boolean).join(" · ")}
              </span>
              <span className="pt-bot-badge pt-bot-badge--own">Your bot</span>
            </div>
          ))}
        </div>
      )}

      {foreign.length > 0 && (
        <div className="pt-bot-list" style={{ marginTop: owned.length > 0 ? 20 : 0 }}>
          <p className="pt-bot-section-label">Other teams ({foreign.length})</p>
          {foreign.map((b) => (
            <div key={b.id} className="pt-bot-item">
              <span className="pt-bot-name">{b.name}</span>
              <span className="pt-bot-meta">
                {[b.family, b.strategyType].filter(Boolean).join(" · ")}
              </span>
              <span className="pt-bot-badge pt-bot-badge--foreign">Public</span>
            </div>
          ))}
        </div>
      )}

      {bots.length === 0 && (
        <p className="play-tournament-empty">No bots registered on the public server yet.</p>
      )}
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function PublicTournamentLobbyPage() {
  const navigate     = useNavigate();
  const [activeTab, setActiveTab]               = useState<TabId>("tournaments");
  const [data, setData]                         = useState<PublicTournamentListResponse | null>(null);
  const [loading, setLoading]                   = useState(true);
  const [error, setError]                       = useState<string | null>(null);
  const [bots, setBots]                         = useState<PublicRegisteredBot[]>([]);
  const [ownedIds, setOwnedIds]                 = useState<Set<string>>(new Set());
  const [showRegisterModal, setShowRegisterModal] = useState(false);
  const [serverOnline, setServerOnline]         = useState<boolean | null>(null);
  const [games, setGames]                       = useState<TournamentGameRow[]>([]);
  const [analyticsExports, setAnalyticsExports] = useState<PublicAnalyticsExport[]>([]);
  const [gamesLoading, setGamesLoading]         = useState(false);
  const [gamesErrors, setGamesErrors]           = useState<string[]>([]);
  const [gamesLoaded, setGamesLoaded]           = useState(false);
  const gamesLoadingRef                         = useRef(false);
  const canManage = canManagePublicTournaments();

  async function loadGames(listData: PublicTournamentListResponse) {
    if (gamesLoadingRef.current) return;
    gamesLoadingRef.current = true;
    setGamesLoading(true);
    try {
      const result = await loadTournamentGames(listData);
      setGames(result.games);
      setAnalyticsExports(result.exports);
      setGamesErrors(result.errors);
      setGamesLoaded(true);
    } finally {
      gamesLoadingRef.current = false;
      setGamesLoading(false);
    }
  }

  function loadAll(active: { value: boolean }) {
    setGamesLoaded(false);
    setLoading(true);
    setError(null);
    listPublicTournaments()
      .then((d) => {
        if (active.value) { setData(d); setServerOnline(true); }
      })
      .catch((e: unknown) => {
        if (active.value) {
          setError(e instanceof Error ? e.message : "Failed to load tournaments");
          setServerOnline(false);
        }
      })
      .finally(() => { if (active.value) setLoading(false); });

    void listPublicBots()
      .then((b) => { if (active.value) setBots(b); })
      .catch(() => {});

    if (canManage) {
      void getMyTournamentBots()
        .then((owned) => { if (active.value) setOwnedIds(new Set(owned.map((o) => o.tournamentServerBotId))); })
        .catch(() => {});
    }
  }

  useEffect(() => {
    const active = { value: true };
    loadAll(active);
    return () => { active.value = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if ((activeTab === "allGames" || activeTab === "myGames" || activeTab === "analytics") && data !== null && !gamesLoaded) {
      void loadGames(data);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab, data, gamesLoaded]);

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">

        {/* ── Header ─────────────────────────────────────────────────────── */}
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Tournament Lab</h1>
            <p className="play-tournament-subtitle">
              Public tournament server
              {serverOnline === true  && <span className="pt-server-status pt-server-status--online">online</span>}
              {serverOnline === false && <span className="pt-server-status pt-server-status--offline">unreachable</span>}
            </p>
          </div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
            <Button variant="secondary" size="sm"
              onClick={() => { const a = { value: true }; loadAll(a); }}>
              Refresh
            </Button>
            {canManage && (
              <>
                <Button variant="secondary" size="sm" onClick={() => setShowRegisterModal(true)}>
                  + Register bot
                </Button>
                <Button variant="primary" size="sm" onClick={() => navigate("/play-tournament/create")}>
                  + Create tournament
                </Button>
              </>
            )}
            {!canManage && (
              <span className="pt-auth-note">Sign in to create tournaments or register bots.</span>
            )}
            <Button variant="secondary" size="sm" onClick={() => navigate("/")}>
              ← Back
            </Button>
          </div>
        </div>

        {/* ── Tab bar ────────────────────────────────────────────────────── */}
        <div className="pt-tab-bar" role="tablist">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.id}
              className={`pt-tab${activeTab === tab.id ? " pt-tab--active" : ""}`}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* ── Tab panels ─────────────────────────────────────────────────── */}
        {activeTab === "tournaments" && (
          <div className="pt-panel">
            <TournamentsTab
              data={data}
              loading={loading}
              error={error}
              canManage={canManage}
              onCreateClick={() => navigate("/play-tournament/create")}
              onSelect={(id) => navigate(`/play-tournament/${id}`)}
            />
          </div>
        )}

        {activeTab === "myBots" && (
          <div className="pt-panel">
            <MyBotsTab
              bots={bots}
              ownedIds={ownedIds}
              canRegister={canManage}
              onRegisterClick={() => setShowRegisterModal(true)}
            />
          </div>
        )}

        {activeTab === "quickTest" && (
          <div className="pt-panel">
            <QuickTestTab
              bots={bots}
              ownedIds={ownedIds}
              canManage={canManage}
              onRegisterClick={() => setShowRegisterModal(true)}
              onSwitchToMyBots={() => setActiveTab("myBots")}
              onTournamentCreated={() => { const a = { value: true }; loadAll(a); }}
            />
          </div>
        )}
        {activeTab === "allGames" && (
          <div className="pt-panel">
            <GamesTab
              games={games}
              ownedIds={ownedIds}
              loading={gamesLoading}
              errors={gamesErrors}
              filterOwned={false}
              onRefresh={() => { setGamesLoaded(false); const a = { value: true }; loadAll(a); }}
            />
          </div>
        )}
        {activeTab === "myGames" && !canManage && (
          <div className="pt-panel">
            <div className="pt-placeholder">
              <p className="pt-placeholder-title">My Games</p>
              <p className="pt-placeholder-body">Sign in to see your own bot games.</p>
            </div>
          </div>
        )}
        {activeTab === "myGames" && canManage && (
          <div className="pt-panel">
            <GamesTab
              games={games}
              ownedIds={ownedIds}
              loading={gamesLoading}
              errors={gamesErrors}
              filterOwned={true}
              onRefresh={() => { setGamesLoaded(false); const a = { value: true }; loadAll(a); }}
            />
          </div>
        )}
        {activeTab === "analytics" && (
          <div className="pt-panel">
            <AnalyticsTab
              games={games}
              exports={analyticsExports}
              tournamentList={data}
              ownedIds={ownedIds}
              loading={gamesLoading}
              errors={gamesErrors}
              canManage={canManage}
              onRefresh={() => { setGamesLoaded(false); const a = { value: true }; loadAll(a); }}
            />
          </div>
        )}

      </div>

      {showRegisterModal && (
        <RegisterBotModal
          publicBots={bots}
          ownedIds={ownedIds}
          onClose={() => setShowRegisterModal(false)}
          onRegistered={(bot) => {
            setShowRegisterModal(false);
            setBots((prev) => prev.some((b) => b.id === bot.id) ? prev : [...prev, bot]);
            setOwnedIds((prev) => new Set([...prev, bot.id]));
            setActiveTab("myBots");
          }}
        />
      )}
    </div>
  );
}
