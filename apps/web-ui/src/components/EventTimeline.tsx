import type { GameState } from "../api/types";
import type { WsEvent } from "../api/wsTypes";
import "./EventTimeline.css";

export type LiveTimelineEvent = {
  id: string;
  receivedAt: string;
  event: WsEvent;
};

type TimelineItem = {
  id: string;
  eventType: string;
  detail: string;
  source: "state" | "live";
  occurredAt?: string;
};

type EventTimelineProps = {
  game: GameState | null | undefined;
  liveEvents: LiveTimelineEvent[];
};

function moveDetail(move: { from: string; to: string; promotion?: string }): string {
  return `${move.from} -> ${move.to}${move.promotion ? ` (${move.promotion})` : ""}`;
}

function liveDetail(event: WsEvent): string {
  switch (event.eventType) {
    case "MoveApplied":
      return `${moveDetail(event.move)} by ${event.playerWhoMoved}`;
    case "MoveRejected":
      return `${moveDetail(event.move)} rejected: ${event.reason}`;
    case "GameFinished":
      return event.winner ? `${event.status}, winner ${event.winner}` : event.status;
    case "GameResigned":
      return `winner ${event.winner}`;
    case "SessionLifecycleChanged":
      return `${event.from} -> ${event.to}`;
    case "SessionCreated":
      return `${event.mode}, ${event.whiteController} vs ${event.blackController}`;
    case "AITurnRequested":
      return `AI requested for ${event.currentPlayer}`;
    case "AITurnCompleted":
      return moveDetail(event.move);
    case "AITurnFailed":
      return event.reason;
    case "PromotionPending":
      return "promotion choice required";
    case "SessionCancelled":
      return "session cancelled";
  }
}

function stateItems(game: GameState | null | undefined): TimelineItem[] {
  if (!game) return [];

  const items: TimelineItem[] = [
    {
      id: `${game.id}:created`,
      eventType: "GameCreated",
      detail: `gameId ${game.id}`,
      source: "state"
    },
    ...game.moves.map((move) => ({
      id: `${game.id}:move:${move.ply}`,
      eventType: "MoveApplied",
      detail: `#${move.ply} ${move.notation} ${move.from} -> ${move.to}`,
      source: "state" as const
    }))
  ];

  if (game.status === "checkmate" || game.status === "draw" || game.status === "resigned") {
    items.push({
      id: `${game.id}:finished`,
      eventType: "GameFinished",
      detail: game.winner ? `${game.status}, winner ${game.winner}` : game.status,
      source: "state"
    });
  }

  return items;
}

function liveItems(game: GameState | null | undefined, liveEvents: LiveTimelineEvent[]): TimelineItem[] {
  if (!game) return [];

  return liveEvents
    .filter((entry) => entry.event.gameId === game.id)
    .map((entry) => ({
      id: entry.id,
      eventType: entry.event.eventType,
      detail: liveDetail(entry.event),
      source: "live",
      occurredAt: entry.receivedAt
    }));
}

export default function EventTimeline({ game, liveEvents }: EventTimelineProps) {
  const items = [...stateItems(game), ...liveItems(game, liveEvents)].slice(-40);

  return (
    <section className="event-timeline-panel" aria-label="Kafka event timeline">
      <header>
        <h2>Event Timeline</h2>
        <p>{game ? `gameId ${game.id}` : "No active game."}</p>
      </header>

      <div className="event-timeline-list">
        {items.length === 0 ? (
          <div className="event-timeline-empty">No events yet.</div>
        ) : (
          items.map((item, index) => (
            <div key={item.id} className={`event-timeline-item event-${item.eventType}`}>
              <span className="event-index">{index + 1}</span>
              <div className="event-body">
                <div className="event-title-row">
                  <strong>{item.eventType}</strong>
                  <span>{item.source === "live" ? "live" : "state"}</span>
                </div>
                <p>{item.detail}</p>
                {item.occurredAt ? <time>{new Date(item.occurredAt).toLocaleTimeString()}</time> : null}
              </div>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
