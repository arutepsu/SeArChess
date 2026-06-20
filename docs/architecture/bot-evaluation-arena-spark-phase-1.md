# Bot Evaluation Arena + Spark Analytics — Phase 1 Architecture

**Status:** Design foundation  
**Phase:** 1 — Architecture & Contracts  
**Date:** 2026-06-13

---

## 1. Problem Statement

The searchess project has a chess engine (SearchessAI) but no systematic way to measure its strength relative to known baselines. Without a controlled evaluation harness, it is impossible to answer questions like:

- Is SearchessAI-v1 stronger than a pure random mover?
- How does it compare against Stockfish at shallow depths?
- Is a new model version an improvement or a regression?

Manual game inspection does not scale. We need a repeatable, automated arena that runs bots against each other, emits structured game events, and lets Apache Spark aggregate meaningful statistics.

---

## 2. System Goal

Build a **Bot Evaluation Arena** that:

1. Orchestrates games between two bot participants (white vs black).
2. Emits normalized, structured events for every game phase (start / move / finish).
3. Writes those events to a JSONL file as the primary integration point.
4. Is designed so the same event schema can later be published to Kafka without structural changes.
5. Allows Apache Spark to read the JSONL file (or a Kafka topic) and produce analytics dashboards.

The arena must be decoupled from any specific bot implementation. Bots plug in via a clean interface. Spark plugs in via the shared event schema.

---

## 3. Main Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Bot Evaluation Arena                             │
│                                                                          │
│  ┌────────────┐   ┌────────────┐   ┌──────────────────────────────────┐ │
│  │  Bot White │   │  Bot Black │   │        Tournament Scheduler      │ │
│  │ (BotPlayer)│   │ (BotPlayer)│   │  round-robin / single / Swiss    │ │
│  └─────┬──────┘   └─────┬──────┘   └──────────────┬───────────────────┘ │
│        │                │                          │                     │
│        └────────┬────────┘                         │                     │
│                 ▼                                  │                     │
│         ┌──────────────┐                           │                     │
│         │  GameRunner  │◄──────────────────────────┘                     │
│         │  (per game)  │                                                  │
│         └──────┬───────┘                                                  │
│                │  emits GameEvent (sealed trait)                          │
│                ▼                                                          │
│        ┌───────────────┐                                                  │
│        │ EventEmitter  │                                                  │
│        │  (interface)  │                                                  │
│        └───────┬───────┘                                                  │
│                │                                                          │
│        ┌───────▼────────┐                                                 │
│        │ JsonlFileWriter│  ── game-events.jsonl                           │
│        └────────────────┘                                                 │
│               (Phase 1)                                                   │
│                                                                           │
│        ┌────────────────┐                                                 │
│        │  KafkaProducer │  ── topic: game-events  (Phase 2, not yet)      │
│        └────────────────┘                                                 │
└──────────────────────────────────────────────────────────────────────────┘

                                      │
                      game-events.jsonl (append-only)
                                      │
                                      ▼
┌─────────────────────────────────────────────────────┐
│                  Spark Analytics Job                │
│                                                     │
│  ┌──────────────┐   ┌──────────────────────────┐   │
│  │ Batch Reader │   │  Structured Streaming     │   │
│  │ (JSONL file) │   │  (Kafka, Phase 2)         │   │
│  └──────┬───────┘   └──────────────────────────┘   │
│         │                                           │
│         ▼                                           │
│  ┌──────────────────────────────────────────────┐  │
│  │             Analytics Aggregations           │  │
│  │  leaderboard / win-rate / H2H matrix / ...   │  │
│  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

---

## 4. Module Boundaries

| Module | Responsibility | Lives In |
|---|---|---|
| `arena-events` | `BotFamily` enum, `GameEvent` sealed trait, JSON codec (`GameEventJson`), `EventEmitter` interface | `backend/jobs/bot-arena/arena-events` |
| `arena-core` | `BotProfile` metadata model, `BotPlayer` interface, `GameRunner` skeleton, `Tournament` skeleton | `backend/jobs/bot-arena/arena-core` |
| `arena-bots-heuristic` | `RandomBot`, `CaptureFirstBot`, `MaterialGreedyBot` | `backend/jobs/bot-arena/bots/heuristic` |
| `arena-bots-uci` | UCI engine adapter (Stockfish, LCZero) | `backend/jobs/bot-arena/bots/uci` |
| `arena-bots-ai-service` | HTTP adapter for SearchessAI REST endpoint | `backend/jobs/bot-arena/bots/ai-service` |
| `arena-bots-imported` | Lichess game import, replayed moves as a bot | `backend/jobs/bot-arena/bots/imported` |
| `arena-writer-jsonl` | Append-only JSONL file writer implementing `EventEmitter` | `backend/jobs/bot-arena/writers/jsonl` |
| `arena-writer-kafka` | Kafka producer (Phase 6 stub, not implemented) | `backend/jobs/bot-arena/writers/kafka` |
| `spark-analytics` | Spark batch jobs, aggregation queries | `backend/jobs/spark-analytics` (new) |

**Dependency rules:**
- `arena-events` depends on nothing in this project (only ujson).
- `arena-core` depends on `arena-events` and `searchess-domain`.
- `arena-writer-jsonl` depends on `arena-events` only (not on `arena-core`).
- `arena-bots-*` depend on `arena-core`.
- `spark-analytics` has no dependency on any arena module; it reads JSON only.
- No arena module may depend on Spark, Kafka, or any concrete writer implementation.

---

## 5. Bot Participant Families

Each bot belongs to exactly one family. The family determines the adapter pattern required and what metadata Spark can use for family-level comparison.

| Family | Description | Adapter Pattern |
|---|---|---|
| `heuristic` | Pure Scala move selectors; no external process | In-process function `(Position) => Move` |
| `uci-engine` | External process communicating over UCI protocol | Spawn process, pipe stdin/stdout, parse `bestmove` |
| `ai-service` | HTTP REST call to a running SearchessAI service | HTTP client, `POST /move`, parse JSON response |
| `imported-external` | Replay of moves from an external source (Lichess PGN) | Iterator over pre-recorded move list |
| `future-tournament-api` | Connects to a live tournament API (not yet defined) | TBD — reserved for future phases |

---

## 6. Initial Bot Catalog

### Must-Have (Phase 1 implementation targets)

| Bot ID | Family | Strategy | Engine / Model |
|---|---|---|---|
| `RandomBot` | `heuristic` | Uniform random legal move | — |
| `CaptureFirstBot` | `heuristic` | Prefers captures, random otherwise | — |
| `MaterialGreedyBot` | `heuristic` | Maximizes immediate material gain (no lookahead) | — |
| `StockfishDepth1` | `uci-engine` | Stockfish with `depth 1` | Stockfish (latest) |
| `StockfishDepth3` | `uci-engine` | Stockfish with `depth 3` | Stockfish (latest) |

### Should-Have (Phase 2 implementation targets)

| Bot ID | Family | Strategy | Engine / Model |
|---|---|---|---|
| `StockfishFast` | `uci-engine` | Stockfish with `movetime 100ms` | Stockfish (latest) |
| `StockfishSlow` | `uci-engine` | Stockfish with `movetime 5000ms` | Stockfish (latest) |
| `SearchessAI-v1` | `ai-service` | SearchessAI REST endpoint | searchess-ai v1 |

### Optional (Phase 3+)

| Bot ID | Family | Strategy | Engine / Model |
|---|---|---|---|
| `LCZero` | `uci-engine` | LCZero neural network | LCZero (latest) |
| `LichessImportedBot` | `imported-external` | Replays Lichess game moves | Lichess PGN import |

---

## 7. Event Schema Design

All events are **flat JSON records**: every field sits at the top level of the JSON object. There are no nested payload sub-objects. Events share a set of common envelope fields; the remaining fields are event-specific. Spark must filter on `eventType` before reading event-specific columns.

### 7.1 Common Envelope Fields

| Field | Type | Description |
|---|---|---|
| `schemaVersion` | `String` | Schema version for forward-compatibility; currently `"1"` |
| `eventType` | `String` | Discriminator: `GameStarted`, `MovePlayed`, `GameFinished` |
| `eventId` | `String` (UUID) | Globally unique event identifier |
| `timestamp` | `String` (ISO 8601) | UTC wall-clock time of event emission |
| `tournamentId` | `String` | Identifies the tournament or evaluation run |
| `gameId` | `String` (UUID) | Identifies the specific game |

### 7.2 `GameStarted` — Event-Specific Fields

| Field | Type | Description |
|---|---|---|
| `whiteBotId` | `String` | Bot identifier for white |
| `blackBotId` | `String` | Bot identifier for black |
| `whiteBotFamily` | `String` | Family enum value for white |
| `blackBotFamily` | `String` | Family enum value for black |
| `whiteStrategyType` | `String` | Human-readable strategy label for white |
| `blackStrategyType` | `String` | Human-readable strategy label for black |
| `whiteEngineType` | `String` | Engine name/version or `"none"` |
| `blackEngineType` | `String` | Engine name/version or `"none"` |
| `whiteModelVersion` | `String` | Model version or `"none"` |
| `blackModelVersion` | `String` | Model version or `"none"` |

### 7.3 `MovePlayed` — Event-Specific Fields

| Field | Type | Description |
|---|---|---|
| `botId` | `String` | Bot that played the move |
| `moveUci` | `String` | Move in UCI notation (e.g., `e2e4`, `e7e8q`) |
| `plyNumber` | `Int` | Half-move count (1-indexed, white=odd, black=even) |
| `durationMillis` | `Long` | Time the bot spent selecting this move |

### 7.4 `GameFinished` — Event-Specific Fields (Spark-optimized)

This event is the primary analytics target. Together with the common envelope fields, all fields are flat — no nesting — to allow direct Spark DataFrame column access.

| Field | Type | Description |
|---|---|---|
| `whiteBotId` | `String` | Bot identifier for white |
| `blackBotId` | `String` | Bot identifier for black |
| `winnerBotId` | `String \| null` | Winner's bot ID, null on draw |
| `loserBotId` | `String \| null` | Loser's bot ID, null on draw |
| `result` | `String` | `"white"`, `"black"`, or `"draw"` |
| `whiteBotFamily` | `String` | Family enum value for white |
| `blackBotFamily` | `String` | Family enum value for black |
| `whiteStrategyType` | `String` | Human-readable strategy label for white |
| `blackStrategyType` | `String` | Human-readable strategy label for black |
| `whiteEngineType` | `String` | Engine name/version or `"none"` |
| `blackEngineType` | `String` | Engine name/version or `"none"` |
| `whiteModelVersion` | `String` | Model version or `"none"` |
| `blackModelVersion` | `String` | Model version or `"none"` |
| `totalMoves` | `Int` | Full moves (each player's turn = 1 full move) |
| `totalPly` | `Int` | Half moves (each individual play = 1 ply) |
| `durationMillis` | `Long` | Total wall-clock time of the game |
| `terminationReason` | `String` | `"checkmate"`, `"stalemate"`, `"draw-50move"`, `"draw-repetition"`, `"draw-insufficient"`, `"timeout"`, `"error"` |

---

## 8. File-First JSONL Contract

### File Name
```
game-events.jsonl
```

### Format Rules
- One complete JSON object per line, no trailing commas.
- Lines are appended in emission order; the file is never rewritten.
- UTF-8 encoding, Unix line endings (`\n`).
- Empty lines are forbidden; a partial line indicates a write error.

### Example Lines

```jsonl
{"schemaVersion":"1","eventType":"GameStarted","eventId":"a1b2c3d4-...","timestamp":"2026-06-13T10:00:00Z","tournamentId":"eval-run-001","gameId":"g001","whiteBotId":"RandomBot","blackBotId":"StockfishDepth1","whiteBotFamily":"heuristic","blackBotFamily":"uci-engine","whiteStrategyType":"random","blackStrategyType":"depth-1","whiteEngineType":"none","blackEngineType":"Stockfish","whiteModelVersion":"none","blackModelVersion":"none"}
{"schemaVersion":"1","eventType":"MovePlayed","eventId":"b2c3d4e5-...","timestamp":"2026-06-13T10:00:00.012Z","tournamentId":"eval-run-001","gameId":"g001","botId":"RandomBot","moveUci":"e2e4","plyNumber":1,"durationMillis":2}
{"schemaVersion":"1","eventType":"MovePlayed","eventId":"c3d4e5f6-...","timestamp":"2026-06-13T10:00:00.105Z","tournamentId":"eval-run-001","gameId":"g001","botId":"StockfishDepth1","moveUci":"e7e5","plyNumber":2,"durationMillis":91}
{"schemaVersion":"1","eventType":"GameFinished","eventId":"d4e5f6a7-...","timestamp":"2026-06-13T10:00:47Z","tournamentId":"eval-run-001","gameId":"g001","whiteBotId":"RandomBot","blackBotId":"StockfishDepth1","winnerBotId":"StockfishDepth1","loserBotId":"RandomBot","result":"black","whiteBotFamily":"heuristic","blackBotFamily":"uci-engine","whiteStrategyType":"random","blackStrategyType":"depth-1","whiteEngineType":"none","blackEngineType":"Stockfish","whiteModelVersion":"none","blackModelVersion":"none","totalMoves":23,"totalPly":46,"durationMillis":47100,"terminationReason":"checkmate"}
```

### Spark Read Pattern

```python
# Phase 1 — schema inference (acceptable for local demo)
df = spark.read.json("path/to/game-events.jsonl")
finished = df.filter(df.eventType == "GameFinished")

# Phase 4+ target — explicit schema (stable analytics)
# schema = StructType([StructField("schemaVersion", StringType()), ...])
# df = spark.read.schema(schema).json("path/to/game-events.jsonl")
```

Because all event types share the envelope fields and have flat event-specific fields, Spark infers a union schema. Analytics jobs must always filter on `eventType` before reading event-specific columns. Schema inference is acceptable for Phase 1 local demos; an explicit `StructType` schema is the target for stable batch jobs.

---

## 9. Kafka-Ready Event Contract

> **Kafka is NOT implemented in Phase 1.** This section defines the contract so that the JSONL schema can be reused as the Kafka message value without structural changes.

| Property | Value |
|---|---|
| Topic | `game-events` |
| Key | `gameId` (String) |
| Value | Full JSON event object (same structure as a JSONL line) |
| Partitioning | By `gameId` — all events for a game land on the same partition |
| Ordering guarantee | Per-partition ordering ensures game events arrive in sequence |
| Schema registry | Not required in Phase 1; JSON with documented schema is sufficient |
| Compression | `lz4` recommended for production |

### Migration Path (JSONL → Kafka)

The `EventEmitter` interface abstracts the write destination. Swapping from `JsonlFileWriter` to `KafkaProducer` requires only injecting a different `EventEmitter` implementation. The `GameEvent` → JSON serialization code is shared.

```
Phase 1: GameRunner → EventEmitter → JsonlFileWriter → game-events.jsonl
Phase 2: GameRunner → EventEmitter → KafkaProducer  → topic: game-events
Phase 2: Spark Structured Streaming reads from Kafka instead of JSONL file
```

---

## 10. Spark Analytics Goals

All analytics derive from `GameFinished` events. `GameStarted` and `MovePlayed` events support supplementary queries (e.g., average move latency per bot).

### 10.1 Bot Leaderboard

Rank bots by total points using **classical chess scoring**: win = 1.0, draw = 0.5, loss = 0.0.

### 10.2 Win / Loss / Draw Counts Per Bot

For each `botId`, count how many times it appears as white or black and the outcome:

| botId | wins | losses | draws | games |
|---|---|---|---|---|

### 10.3 Win Rate Per Bot

`win_rate = wins / games` (draws count as neither win nor loss for this metric).

### 10.4 Bot Family Comparison

Aggregate `result` grouped by `(whiteBotFamily, blackBotFamily)` to answer: "do heuristic bots lose to uci-engine bots consistently?"

### 10.5 Head-to-Head Matrix

A matrix where row = white bot, column = black bot, cell = win rate of white.

### 10.6 Average Game Length

`AVG(totalPly)` and `AVG(durationMillis)` per `(whiteBotId, blackBotId)` pairing.

### 10.7 SearchessAI-v1 Comparison Against Baselines

Filter for games where `whiteBotId = 'SearchessAI-v1' OR blackBotId = 'SearchessAI-v1'`, then compute win rate, average game length, and head-to-head results against each baseline bot.

This is the primary evaluation query and the reason the arena exists.

---

## 11. Risks and Tradeoffs

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| UCI process management is fragile (timeouts, crashes) | Medium | Medium | Wrap in a managed resource; emit `terminationReason: "error"` on failure |
| JSONL file grows unbounded during large tournaments | Low | Low | Rotate by `tournamentId`; Spark reads per-tournament files |
| Spark schema inference breaks if new event fields are added | Medium | Low | Add fields only at the end; use `PERMISSIVE` mode; document schema version |
| SearchessAI REST endpoint is slow, slowing arena throughput | Medium | Medium | Configurable per-bot timeout; emit `terminationReason: "timeout"` |
| LCZero requires GPU; not available in CI | High | Low | Mark `LCZero` as optional; skip in CI unless GPU node is available |
| Move legality: bots may return illegal moves | Low | High | `GameRunner` validates every move against `searchess-domain`; illegal move = forfeit |
| Imported bot (Lichess) may have gaps in move sequence | Low | Medium | Fail-fast on import; do not silently skip moves |
| Parallel UCI games sharing one Stockfish process corrupt state | High | High | Each concurrent `GameRunner` must own its own UCI process; enforced by §13.5 |
| Spark job hardcodes `local[*]` and breaks on cluster | Medium | Medium | All Spark settings must be externally configurable; no hardcoded master URLs (§13.2) |

---

## 12. Implementation Phases

### Phase 1A — Architecture Document

- [x] Architecture document

### Phase 1B — Compile-Safe Contracts & Module Stubs

- [x] `BotFamily` enum in `arena-events`
- [x] `GameEvent` sealed trait + `GameEventJson` codec in `arena-events`
- [x] `EventEmitter` interface in `arena-events`
- [x] `BotProfile` metadata model in `arena-core`
- [x] `BotPlayer` trait in `arena-core`
- [x] `GameRunner` skeleton in `arena-core`
- [x] `Tournament` trait skeleton in `arena-core`
- [x] `JsonlFileWriter` implementation in `arena-writer-jsonl`
- [x] sbt module stubs: `arenaEvents`, `arenaCore`, `arenaWriterJsonl`, `sparkAnalytics`
- [x] `game-events.sample.jsonl` fixture
- [x] `GameEventJsonSpec` round-trip tests

### Phase 2 — Heuristic Bots + Game Loop

- [ ] Implement `RandomBot`, `CaptureFirstBot`, `MaterialGreedyBot`
- [ ] Implement full `GameRunner` game loop using `searchess-domain`
- [ ] Wire `EventEmitter` → `JsonlFileWriter`
- [ ] Write first JSONL output

### Phase 3 — UCI Engine Adapter

- [ ] Implement UCI process manager
- [ ] Implement `StockfishDepth1`, `StockfishDepth3`
- [ ] Run heuristic vs Stockfish games; validate JSONL output

### Phase 4 — Tournament Scheduler + Spark Batch

- [ ] Implement round-robin `Tournament`
- [ ] Upgrade `JsonlFileWriter` to buffered/resource-safe writer (see §14.4)
- [ ] Add `schemaVersion` validation to `GameEventJson.decode` (see §14.1)
- [ ] Write first Spark batch job reading `game-events.jsonl` — all settings configurable (master, input path, output path)
- [ ] Switch `sparkAnalytics` module to `scalaVersion := "2.13.x"`
- [ ] Produce bot leaderboard and H2H matrix

### Phase 5 — SearchessAI Adapter + Evaluation

- [ ] Implement `ai-service` HTTP adapter with configurable baseUrl, endpointPath, timeoutMillis, modelVersion (see §13.4)
- [ ] Implement `SearchessAI-v1` bot backed by the adapter
- [ ] Run SearchessAI vs baselines; produce evaluation report via Spark

### Phase 6 — Kafka Integration

- [ ] Implement `KafkaProducer` as an `EventEmitter`
- [ ] Implement Spark Structured Streaming job reading from `game-events` topic
- [ ] Switch arena to dual-write or Kafka-only mode

---

## 13. Resolved Decisions

Previously open questions, now decided.

### 13.1 sbt Module Layout

`bot-arena` lives under `backend/jobs/`, not `tools/`.

**Reason:** `bot-arena` is a runtime application/job — it can run tournaments, call HTTP services, manage UCI engine processes, and later produce Kafka events. It is not a helper script. `tools/` is reserved for thin developer utilities (shell scripts, generators, one-off data processors).

### 13.2 Spark Runtime

- **Local development and CI:** `local[*]` mode, reading from the JSONL file on the local filesystem.
- **Long-term target:** cluster mode, Kubernetes-compatible (e.g., `spark-submit` to a K8s cluster or a managed service).
- **Constraint:** Spark jobs must never hardcode paths, master URLs, or checkpoint directories. All such values must come from configuration (environment variables or a config file). This keeps the same job binary runnable in both modes without code changes.

### 13.3 Tournament Persistence

- **Phase 1–3:** No database required. The JSONL file is the single source of truth for all game event data. Tournament metadata (start time, bots enrolled, number of games) lives in the `GameStarted` events and in the tournament run's operational config.
- **Long-term:** Persist tournament metadata and operational state (pairing schedule, scores, status) in the operational database.
- **Invariant:** Spark analytics must always read from event streams or files, never from operational DB tables. Analytics and operational data must remain decoupled.

### 13.4 SearchessAI Endpoint

Use the existing `ai-service`. The adapter must be fully configurable:

| Config | Description |
|---|---|
| `baseUrl` | e.g. `http://localhost:8080` |
| `endpointPath` | Prefer a dedicated evaluation path such as `/evaluation/move` or `/bot/move` if the existing endpoint lacks metadata (model version, elapsed time). Fall back to the current path if no dedicated endpoint is available. |
| `timeoutMillis` | Per-request timeout |
| `modelVersion` | Injected into `BotProfile` and written to `GameFinished` events |

The arena must not import `ai-service` source directly. It communicates only via HTTP using the `aiContract` DTOs or a new evaluation-specific contract.

### 13.5 Concurrency

- **Phase 2–4:** Run games sequentially. One active `GameRunner` at a time.
- **Phase 4+:** Add bounded configurable parallelism. The maximum number of concurrent games must be a config value (e.g. `arena.parallelism = 4`).
- **UCI constraint:** Parallel games that share a single Stockfish process are not safe. Each concurrent `GameRunner` must own its own UCI process instance.
- **Default:** Sequential first; parallelism is an optimisation, not a correctness requirement.

---

## 14. Implementation Notes

Design constraints and follow-up obligations recorded for future phases. These are non-negotiable before the affected phase ships.

### 14.1 `GameEventJson` Must Validate `schemaVersion` Before Phase 4

`decode` currently reads `schemaVersion` from JSON but does not validate it. Before Phase 4 Spark analytics are wired up, `decode` must reject events where `schemaVersion != "1"` (or the current expected version) and return a `Left`. Otherwise, a schema-incompatible file could be silently processed and produce wrong analytics results.

### 14.2 `BotFamily` in Code vs. JSON

Internal Scala code must use the `BotFamily` enum (e.g. `BotFamily.UciEngine`), never the raw string. JSON stores the stable lowercase-kebab representation (e.g. `"uci-engine"`). The codec layer — and only the codec layer — is responsible for the conversion. `BotProfile.family` is typed as `BotFamily`; the string form appears only in `GameEvent` fields and in `GameEventJson`.

### 14.3 `MovePlayed` May Gain Bot Metadata in a Later Phase

The current `MovePlayed` event carries only `botId`. If Spark aggregations on per-move data (e.g. average move latency per bot family, latency vs. engine depth) become necessary, `MovePlayed` should be extended with the same bot metadata fields as `GameStarted` (`botFamily`, `strategyType`, `engineType`, `modelVersion`). This is a schema-additive change and will require a `schemaVersion` bump. Do not add these fields speculatively.

### 14.4 `JsonlFileWriter` Must Become Buffered/Resource-Safe Before Large Tournaments

The current implementation opens and closes the OS file on every `emit` call. This is correct for Phase 1B validation but will be a throughput bottleneck for tournaments with thousands of games. Before Phase 4, `JsonlFileWriter` should:

1. Hold an open `BufferedWriter` for the lifetime of a tournament run.
2. Flush after each `GameFinished` event (not after every `MovePlayed`).
3. Implement a `close()` method and be wrapped in a managed resource (e.g. `scala.util.Using`).
