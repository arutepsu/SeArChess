import { useMemo, useState, type CSSProperties } from "react";
import type { LiveTimelineEvent } from "../components/EventTimeline";
import type { WsEvent } from "../api/wsTypes";
import "./LiveEventStreamPage.css";

type EventStatus = "processed" | "retry" | "dlq";
type StatusFilter = "all" | EventStatus;

interface Props {
  events: LiveTimelineEvent[];
  liveConnection: "idle" | "connecting" | "live" | "disconnected";
  onBack: () => void;
  onClear: () => void;
}

type AdminEventRow = {
  id: string;
  eventType: string;
  gameId: string;
  producer: string;
  timestamp: string;
  correlationId: string;
  status: EventStatus;
  partition?: number;
  offset?: number;
  partitionSource: "event" | "derived";
  offsetSource: "event" | "local";
  originalTopic?: string;
  failureCategory?: string;
  failureReason?: string;
  originalPayload: string;
};

const statusFilters: StatusFilter[] = ["all", "processed", "retry", "dlq"];
const assumedPartitionCount = 3;

function readString(event: WsEvent, key: string): string | undefined {
  const value = (event as unknown as Record<string, unknown>)[key];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function readNumber(event: WsEvent, key: string): number | undefined {
  const value = (event as unknown as Record<string, unknown>)[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function inferProducer(event: WsEvent): string {
  switch (event.eventType) {
    case "AITurnRequested":
    case "AITurnCompleted":
    case "AITurnFailed":
      return "game-service:ai-orchestrator";
    default:
      return "game-service";
  }
}

function readStatus(event: WsEvent): EventStatus {
  const status = readString(event, "status");
  return status === "retry" || status === "dlq" || status === "processed"
    ? status
    : "processed";
}

function toAdminRow(entry: LiveTimelineEvent): AdminEventRow {
  const event = entry.event;
  const partition = readNumber(event, "partition") ?? readNumber(event, "originalPartition");
  const offset = readNumber(event, "offset") ?? readNumber(event, "originalOffset");
  const failureReason = readString(event, "failureReason") ?? readString(event, "reason");

  return {
    id: entry.id,
    eventType: event.eventType,
    gameId: event.gameId,
    producer: readString(event, "producer") ?? inferProducer(event),
    timestamp: readString(event, "occurredAt") ?? entry.receivedAt,
    correlationId: readString(event, "correlationId") ?? event.sessionId,
    status: readStatus(event),
    partition,
    offset,
    partitionSource: partition === undefined ? "derived" : "event",
    offsetSource: offset === undefined ? "local" : "event",
    originalTopic: readString(event, "originalTopic") ?? "searchess.game.events.v1",
    failureCategory: readString(event, "failureCategory") ?? (readStatus(event) === "dlq" ? "NonRetryable" : undefined),
    failureReason,
    originalPayload: JSON.stringify(event, null, 2)
  };
}

function shortId(value: string): string {
  return value.length > 18 ? `${value.slice(0, 8)}...${value.slice(-6)}` : value;
}

function stablePartition(gameId: string): number {
  let hash = 0;
  for (let i = 0; i < gameId.length; i += 1) {
    hash = (hash * 31 + gameId.charCodeAt(i)) >>> 0;
  }
  return hash % assumedPartitionCount;
}

function eventOffset(row: AdminEventRow, index: number): number {
  return row.offset ?? index + 1;
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleTimeString();
}

export default function LiveEventStreamPage({ events, liveConnection, onBack, onClear }: Props) {
  const [filter, setFilter] = useState<StatusFilter>("all");
  const [demoEvents, setDemoEvents] = useState<LiveTimelineEvent[]>([]);
  const [investigatedIds, setInvestigatedIds] = useState<Set<string>>(() => new Set());

  const allEvents = useMemo(() => [...events, ...demoEvents], [events, demoEvents]);
  const rows = useMemo(() => allEvents.map(toAdminRow).reverse(), [allEvents]);
  const filteredRows = rows.filter((row) => filter === "all" || row.status === filter);
  const flowRows = rows.slice(0, 12).reverse();

  const counts = rows.reduce(
    (acc, row) => ({ ...acc, [row.status]: acc[row.status] + 1 }),
    { processed: 0, retry: 0, dlq: 0 } satisfies Record<EventStatus, number>
  );

  return (
    <main className="live-event-page">
      <header className="live-event-header">
        <button type="button" className="live-event-back" onClick={onBack}>
          Back
        </button>
        <div>
          <span className={`live-event-connection is-${liveConnection}`}>{liveConnection}</span>
          <h1>Live Event Stream</h1>
        </div>
        <div className="live-event-header-actions">
          <button type="button" className="live-event-demo" onClick={() => setDemoEvents(createDemoEvents())}>
            Demo Mode
          </button>
          <button
            type="button"
            className="live-event-clear"
            onClick={() => {
              setDemoEvents([]);
              setInvestigatedIds(new Set());
              onClear();
            }}
            disabled={allEvents.length === 0}
          >
            Clear
          </button>
        </div>
      </header>

      <section className="live-event-stats" aria-label="Event status totals">
        <div>
          <span>Total</span>
          <strong>{rows.length}</strong>
        </div>
        <div>
          <span>Processed</span>
          <strong>{counts.processed}</strong>
        </div>
        <div>
          <span>Retry</span>
          <strong>{counts.retry}</strong>
        </div>
        <div>
          <span>DLQ</span>
          <strong>{counts.dlq}</strong>
        </div>
      </section>

      <EventFlowMap events={flowRows} />

      <PartitionInspector events={rows} />

      <DlqDashboard
        events={rows}
        investigatedIds={investigatedIds}
        onMarkInvestigated={(id) => setInvestigatedIds((ids) => new Set(ids).add(id))}
        onReplay={(event) => setDemoEvents((items) => [...items, replayEvent(event)])}
      />

      <CorrelationTraceView events={rows} />

      <ConsumerLagHeatmap events={rows} />

      <section className="live-event-panel">
        <div className="live-event-toolbar" role="tablist" aria-label="Filter events by status">
          {statusFilters.map((status) => (
            <button
              key={status}
              type="button"
              role="tab"
              aria-selected={filter === status}
              className={filter === status ? "is-active" : ""}
              onClick={() => setFilter(status)}
            >
              {status}
            </button>
          ))}
        </div>

        <div className="live-event-table-wrap">
          <table className="live-event-table">
            <thead>
              <tr>
                <th>timestamp</th>
                <th>status</th>
                <th>eventType</th>
                <th>gameId</th>
                <th>producer</th>
                <th>correlationId</th>
              </tr>
            </thead>
            <tbody>
              {filteredRows.length === 0 ? (
                <tr>
                  <td className="live-event-empty" colSpan={6}>
                    No events for this filter yet.
                  </td>
                </tr>
              ) : (
                filteredRows.map((row) => (
                  <tr key={row.id}>
                    <td>
                      <time dateTime={row.timestamp}>{formatTime(row.timestamp)}</time>
                    </td>
                    <td>
                      <span className={`live-event-status is-${row.status}`}>{row.status}</span>
                    </td>
                    <td>{row.eventType}</td>
                    <td title={row.gameId}>{shortId(row.gameId)}</td>
                    <td>{row.producer}</td>
                    <td className="live-event-correlation" title={row.correlationId}>
                      {row.correlationId}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}

function replayEvent(event: AdminEventRow): LiveTimelineEvent {
  const now = new Date().toISOString();
  return demoEvent({
    eventType: event.eventType === "CorruptedPayload" ? "MoveApplied" : event.eventType,
    eventId: `replay-${event.id}-${Date.now()}`,
    gameId: event.gameId,
    sessionId: event.correlationId,
    correlationId: event.correlationId,
    occurredAt: now,
    status: "processed",
    payload: {
      replayedFromDlq: event.id,
      failureReason: "Replay after fix"
    }
  });
}

function PartitionInspector({ events }: { events: AdminEventRow[] }) {
  const groups = useMemo(() => {
    const byGame = new Map<string, AdminEventRow[]>();

    events
      .slice()
      .reverse()
      .forEach((event) => {
        const group = byGame.get(event.gameId) ?? [];
        group.push(event);
        byGame.set(event.gameId, group);
      });

    return Array.from(byGame.entries())
      .map(([gameId, gameEvents]) => ({
        gameId,
        events: gameEvents,
        partition: gameEvents.find((event) => event.partition !== undefined)?.partition ?? stablePartition(gameId),
        partitionSource: gameEvents.some((event) => event.partitionSource === "event") ? "event" : "derived",
        offsetSource: gameEvents.some((event) => event.offsetSource === "event") ? "event" : "local"
      }))
      .sort((left, right) => right.events.length - left.events.length);
  }, [events]);

  const inspected = groups[0];

  return (
    <section className="partition-inspector-panel" aria-label="Kafka partition inspector">
      <header className="partition-inspector-header">
        <div>
          <span className="partition-inspector-kicker">Ordering Contract</span>
          <h2>Partition Inspector</h2>
        </div>
        <span className="partition-ordering-badge">ordering guaranteed per gameId</span>
      </header>

      {inspected ? (
        <>
          <div className="partition-inspector-grid">
            <InspectorMetric label="gameId" value={shortId(inspected.gameId)} title={inspected.gameId} />
            <InspectorMetric label="Kafka key" value={shortId(inspected.gameId)} title={inspected.gameId} />
            <InspectorMetric
              label="Partition"
              value={`${inspected.partition}`}
              detail={inspected.partitionSource === "event" ? "from event metadata" : `derived preview / ${assumedPartitionCount}`}
            />
            <InspectorMetric
              label="Offsets"
              value={inspected.events.map(eventOffset).join(" -> ")}
              detail={inspected.offsetSource === "event" ? "from Kafka metadata" : "local sequence preview"}
            />
          </div>

          <div className="partition-chain" aria-label="Events in partition order">
            {inspected.events.map((event, index) => (
              <div key={event.id} className={`partition-chain-item is-${event.status}`}>
                <span>#{eventOffset(event, index)}</span>
                <strong>{event.eventType}</strong>
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="partition-inspector-empty">
          Start a game or run Demo Mode to inspect the gameId partition contract.
        </div>
      )}
    </section>
  );
}

function InspectorMetric({
  label,
  value,
  detail,
  title
}: {
  label: string;
  value: string;
  detail?: string;
  title?: string;
}) {
  return (
    <div className="partition-metric" title={title}>
      <span>{label}</span>
      <strong>{value}</strong>
      {detail ? <small>{detail}</small> : null}
    </div>
  );
}

function DlqDashboard({
  events,
  investigatedIds,
  onMarkInvestigated,
  onReplay
}: {
  events: AdminEventRow[];
  investigatedIds: Set<string>;
  onMarkInvestigated: (id: string) => void;
  onReplay: (event: AdminEventRow) => void;
}) {
  const dlqEvents = events.filter((event) => event.status === "dlq");

  async function copyPayload(payload: string): Promise<void> {
    await navigator.clipboard.writeText(payload);
  }

  function downloadIncident(event: AdminEventRow): void {
    const incident = {
      eventType: event.eventType,
      gameId: event.gameId,
      originalTopic: event.originalTopic,
      originalPartition: event.partition ?? stablePartition(event.gameId),
      originalOffset: event.offset,
      failureCategory: event.failureCategory,
      failureReason: event.failureReason,
      failedAt: event.timestamp,
      originalPayload: JSON.parse(event.originalPayload)
    };
    const blob = new Blob([JSON.stringify(incident, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `dlq-${event.gameId}-${event.id}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  return (
    <section className="ops-panel dlq-dashboard" aria-label="DLQ dashboard">
      <PanelHeader kicker="Incident Response" title="DLQ Dashboard" badge={`${dlqEvents.length} dead letters`} />

      <div className="ops-table-wrap">
        <table className="ops-table">
          <thead>
            <tr>
              <th>eventType</th>
              <th>gameId</th>
              <th>originalTopic</th>
              <th>originalPartition</th>
              <th>originalOffset</th>
              <th>failureCategory</th>
              <th>failureReason</th>
              <th>failedAt</th>
              <th>originalPayload</th>
              <th>actions</th>
            </tr>
          </thead>
          <tbody>
            {dlqEvents.length === 0 ? (
              <tr>
                <td className="ops-empty" colSpan={10}>No DLQ incidents yet.</td>
              </tr>
            ) : (
              dlqEvents.map((event) => (
                <tr key={event.id} className={investigatedIds.has(event.id) ? "is-investigated" : ""}>
                  <td>{event.eventType}</td>
                  <td title={event.gameId}>{shortId(event.gameId)}</td>
                  <td>{event.originalTopic}</td>
                  <td>{event.partition ?? stablePartition(event.gameId)}</td>
                  <td>{event.offset ?? "local"}</td>
                  <td>{event.failureCategory ?? "Unknown"}</td>
                  <td title={event.failureReason}>{event.failureReason ?? "No reason provided"}</td>
                  <td>{formatTime(event.timestamp)}</td>
                  <td><code>{event.originalPayload.slice(0, 42)}...</code></td>
                  <td>
                    <div className="ops-actions">
                      <button type="button" onClick={() => { void copyPayload(event.originalPayload); }}>Copy payload</button>
                      <button type="button" onClick={() => onMarkInvestigated(event.id)}>Mark investigated</button>
                      <button type="button" onClick={() => onReplay(event)}>Replay after fix</button>
                      <button type="button" onClick={() => downloadIncident(event)}>Download incident JSON</button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function CorrelationTraceView({ events }: { events: AdminEventRow[] }) {
  const [query, setQuery] = useState("");
  const correlationIds = Array.from(new Set(events.map((event) => event.correlationId))).filter(Boolean);
  const selectedCorrelationId = query.trim() || correlationIds[0] || "";
  const matches = events
    .filter((event) => selectedCorrelationId && event.correlationId === selectedCorrelationId)
    .slice()
    .reverse();
  const traceBaseUrl =
    (import.meta.env.VITE_TEMPO_URL as string | undefined) ??
    (import.meta.env.VITE_GRAFANA_EXPLORE_URL as string | undefined);

  const traceItems = selectedCorrelationId
    ? [
        { label: "POST /sessions/human-vs-human", detail: selectedCorrelationId },
        ...matches.map((event) => ({ label: event.eventType, detail: `${event.producer} | ${shortId(event.gameId)}` })),
        { label: "history-service projection", detail: "consumer group history-service-game-projector" },
        { label: "MongoDB update", detail: matches.some((event) => event.status === "retry") ? "waiting for retry" : "projected" }
      ]
    : [];

  return (
    <section className="ops-panel correlation-trace" aria-label="Correlation trace view">
      <PanelHeader kicker="Distributed Trace" title="Correlation Trace View" badge={`${matches.length} matched events`} />
      <div className="correlation-controls">
        <input
          type="text"
          value={query}
          placeholder="correlationId"
          onChange={(event) => setQuery(event.currentTarget.value)}
          list="correlation-options"
        />
        <datalist id="correlation-options">
          {correlationIds.map((id) => <option key={id} value={id} />)}
        </datalist>
        <button
          type="button"
          disabled={!traceBaseUrl || !selectedCorrelationId}
          onClick={() => {
            if (traceBaseUrl && selectedCorrelationId) {
              window.open(`${traceBaseUrl}?correlationId=${encodeURIComponent(selectedCorrelationId)}`, "_blank");
            }
          }}
          title={
            traceBaseUrl
              ? `Open trace for ${selectedCorrelationId}`
              : "Set VITE_GRAFANA_EXPLORE_URL or VITE_TEMPO_URL to enable trace links."
          }
        >
          {traceBaseUrl ? "Open Trace" : "Trace not configured"}
        </button>
      </div>
      {!traceBaseUrl ? (
        <p className="trace-config-hint">
          Configure <code>VITE_GRAFANA_EXPLORE_URL</code> or <code>VITE_TEMPO_URL</code> to open real traces.
        </p>
      ) : null}
      <div className="trace-chain">
        {traceItems.length === 0 ? (
          <div className="ops-empty">No correlation id available yet.</div>
        ) : (
          traceItems.map((item, index) => (
            <div key={`${item.label}-${index}`} className="trace-item">
              <span>{index + 1}</span>
              <div>
                <strong>{item.label}</strong>
                <small>{item.detail}</small>
              </div>
            </div>
          ))
        )}
      </div>
    </section>
  );
}

function ConsumerLagHeatmap({ events }: { events: AdminEventRow[] }) {
  const cells = Array.from({ length: 4 }, (_, partition) => {
    const partitionEvents = events.filter((event) => (event.partition ?? stablePartition(event.gameId)) === partition);
    const lag = partitionEvents.reduce((total, event) => {
      if (event.status === "dlq") return total + 120;
      if (event.status === "retry") return total + 14;
      return total;
    }, 0);
    const status = lag >= 100 ? "critical" : lag > 0 ? "warning" : "ok";
    return { partition, lag, status };
  });

  return (
    <section className="ops-panel lag-heatmap" aria-label="Consumer lag heatmap">
      <PanelHeader kicker="Consumer Health" title="Consumer Lag Heatmap" badge="history-service-game-projector" />
      <div className="lag-grid">
        {cells.map((cell) => (
          <div key={cell.partition} className={`lag-cell is-${cell.status}`}>
            <span>Partition {cell.partition}</span>
            <strong>lag {cell.lag}</strong>
          </div>
        ))}
      </div>
    </section>
  );
}

function PanelHeader({ kicker, title, badge }: { kicker: string; title: string; badge: string }) {
  return (
    <header className="ops-panel-header">
      <div>
        <span>{kicker}</span>
        <h2>{title}</h2>
      </div>
      <strong>{badge}</strong>
    </header>
  );
}

function createDemoEvents(): LiveTimelineEvent[] {
  const now = Date.now();
  const gameId = `demo-game-${now}`;
  const correlationId = `demo-correlation-${now}`;
  const sessionId = `demo-session-${now}`;

  return [
    demoEvent({
      eventType: "MoveApplied",
      eventId: "valid-move",
      gameId,
      sessionId,
      correlationId,
      occurredAt: new Date(now).toISOString(),
      status: "processed",
      payload: {
        move: { from: "e2", to: "e4" },
        playerWhoMoved: "white"
      }
    }),
    demoEvent({
      eventType: "MoveRejected",
      eventId: "invalid-move",
      gameId,
      sessionId,
      correlationId,
      occurredAt: new Date(now + 1200).toISOString(),
      status: "processed",
      payload: {
        move: { from: "e2", to: "e5" },
        reason: "Illegal move"
      }
    }),
    demoEvent({
      eventType: "MoveApplied",
      eventId: "mongo-temporary-down",
      gameId,
      sessionId,
      correlationId,
      occurredAt: new Date(now + 2400).toISOString(),
      status: "retry",
      payload: {
        move: { from: "g8", to: "f6" },
        playerWhoMoved: "black",
        failureReason: "MongoDB connection timeout"
      }
    }),
    demoEvent({
      eventType: "CorruptedPayload",
      eventId: "corrupted-payload",
      gameId,
      sessionId,
      correlationId,
      occurredAt: new Date(now + 3600).toISOString(),
      status: "dlq",
      payload: {
        failureReason: "Payload could not be deserialized"
      }
    })
  ];
}

function demoEvent({
  eventType,
  eventId,
  gameId,
  sessionId,
  correlationId,
  occurredAt,
  status,
  payload
}: {
  eventType: string;
  eventId: string;
  gameId: string;
  sessionId: string;
  correlationId: string;
  occurredAt: string;
  status: EventStatus;
  payload: Record<string, unknown>;
}): LiveTimelineEvent {
  return {
    id: `${gameId}:${eventId}`,
    receivedAt: occurredAt,
    event: {
      eventType,
      gameId,
      sessionId,
      producer: status === "retry" || status === "dlq" ? "history-service" : "game-service",
      correlationId,
      occurredAt,
      status,
      ...payload
    } as unknown as WsEvent
  };
}

function EventFlowMap({ events }: { events: AdminEventRow[] }) {
  return (
    <section className="event-flow-panel" aria-label="Kafka event flow map">
      <header className="event-flow-header">
        <div>
          <span className="event-flow-kicker">Kafka Backbone</span>
          <h2>Event Flow Map</h2>
        </div>
        <div className="event-flow-legend" aria-label="Event status colors">
          <span className="legend-dot is-processed" /> processed
          <span className="legend-dot is-retry" /> retry
          <span className="legend-dot is-dlq" /> dlq
        </div>
      </header>

      <div className="event-flow-scroll">
        <div className="event-flow-canvas">
          <svg className="event-flow-lines" viewBox="0 0 920 300" aria-hidden="true">
            <path className="flow-line" d="M105 78H330" />
            <path className="flow-line" d="M430 78H565" />
            <path className="flow-line" d="M665 78H815" />
            <path className="flow-line retry-line" d="M380 112V196H540" />
            <path className="flow-line retry-line" d="M640 196H815" />
          </svg>

          <FlowNode className="node-game" label="game-service" detail="producer" />
          <FlowNode className="node-topic" label="searchess.game.events.v1" detail="Kafka topic" />
          <FlowNode className="node-history" label="history-service" detail="consumer group" />
          <FlowNode className="node-mongo" label="MongoDB" detail="projection" />
          <FlowNode className="node-retry10" label="retry 10s" detail="retry topic" />
          <FlowNode className="node-retry1m" label="retry 1m" detail="retry topic" />
          <FlowNode className="node-dlq" label="DLQ" detail="dead letter" danger />

          {events.length === 0 ? (
            <div className="event-flow-empty">No live events yet.</div>
          ) : (
            events.map((event, index) => (
              <span
                key={event.id}
                className={`event-flow-pulse route-${event.status} is-${event.status}`}
                style={{ "--pulse-index": index } as CSSProperties}
                title={`${event.eventType} | ${event.gameId} | ${event.status}`}
              />
            ))
          )}
        </div>
      </div>
    </section>
  );
}

function FlowNode({
  className,
  label,
  detail,
  danger = false
}: {
  className: string;
  label: string;
  detail: string;
  danger?: boolean;
}) {
  return (
    <div className={`event-flow-node ${className}${danger ? " is-danger" : ""}`}>
      <strong>{label}</strong>
      <span>{detail}</span>
    </div>
  );
}
