# Reactive Streams Assignment Architecture

## 1. Assignment Goal

The university assignment asks for an application stream with a source, one or
more processing flows, and a sink. In Searchess terms, the assignment should be
a small chess-related Pekko Streams pipeline:

```text
Source[String]
  -> Flow(s) that parse and validate Searchess game commands
  -> Flow(s) that process those commands into game results/events
  -> Sink that collects a summary or event envelopes
```

The first source should be a file containing an external Searchess DSL. Kafka is
intentionally deferred to a later lecture/task, where it can replace the file
source and/or final sink without changing the core parsing and processing
flows.

## 2. Current Repository State

`apps/chess-streaming/src/main/scala/chess/streaming/ChessStreamingMain.scala`
already contains a standalone Pekko Streams demo. It currently has:

- a `Source[String, _]` named `movesSource`, built from an in-memory list of
  move-like DSL strings;
- a parser `Flow[String, Either[Throwable, Move], _]`;
- a validator/processor `Flow[Either[Throwable, Move], Either[String, GameState], _]`;
- a `Sink[Either[String, GameState], Future[Done]]` that prints board states or
  errors;
- materialization with `.toMat(consoleSink)(Keep.right).run()`;
- completion handling with `runningStream.onComplete`, which terminates the
  `ActorSystem`.

`build.sbt` defines `lazy val chessStreaming = project.in(file("apps/chess-streaming"))`
and includes the Pekko Streams dependency:

```scala
"org.apache.pekko" %% "pekko-stream" % "1.1.2"
```

The production event system is separate. `EventPublisher`,
`FanOutEventPublisher`, WebSocket publishing, Redis history delivery, and Game
Service event wiring are not a Reactive Streams pipeline. They are production
eventing and delivery infrastructure and should not be used as proof that the
assignment requirement is satisfied.

Files inspected for this architecture note:

- `apps/chess-streaming/src/main/scala/chess/streaming/ChessStreamingMain.scala`
- `build.sbt`
- `docs/architecture/event-module-ownership.md`
- `docs/contracts/game-events-v1.md`
- `docs/architecture/redis-history-delivery.md`

This assignment should evolve `apps/chess-streaming`, not production Game
Service fan-out.

## 3. Chosen Stream Scenario

The chosen source is a file-based external Searchess DSL. The stream reads lines
from a file such as `searchess-game.dsl`, parses each line into a command,
validates the command against the current stream/session context, processes the
command into game state changes, creates event envelopes, and finally collects a
summary or writes envelopes to a sink.

A file DSL is a better first source than keyboard input, random data, or a web
site for this project because it is deterministic, testable, chess-related, and
easy to replay. It also prepares the architecture for Kafka: each file line can
later become one Kafka record value, while the parsing and processing flows stay
the same.

## 4. Proposed DSL

Initial commands:

| Command | Meaning |
|---|---|
| `session <sessionId>` | Start or identify the streamed game session. |
| `players <whitePlayer> <blackPlayer>` | Assign white and black player names. |
| `move <player> <uciMove>` | Submit a move by player name, using UCI-like notation such as `e2e4`. |
| `status` | Request a status event or summary snapshot. |
| `resign <player>` | End the game by resignation from the named player. |

Example input file:

```text
# searchess-game.dsl

session demo-game-1
players Alice Bob
move Alice e2e4
move Bob e7e5
move Alice g1f3
move Bob b8c6
status
resign Bob
```

Blank lines and lines starting with `#` should be ignored by the parser.

## 5. Proposed Source / Flow / Sink Pipeline

Target pipeline:

```text
Source[String]
  -> ParseDslFlow
  -> ValidateCommandFlow
  -> ProcessGameCommandFlow
  -> CreateEventEnvelopeFlow
  -> Batch/Backpressure Flow
  -> Summary/File/Console Sink
```

| Stage | Conceptual input | Conceptual output | Responsibility | Failure behavior |
|---|---|---|---|---|
| `Source[String]` | File path or stream configuration | Raw DSL lines | Read one command line at a time. | File open/read failure fails the stream. |
| `ParseDslFlow` | Raw line | `DslCommand` or parse error | Ignore comments/blank lines, parse command shape, normalize move text. | Invalid syntax becomes a structured parse error; fatal parser bugs fail the stream. |
| `ValidateCommandFlow` | `DslCommand` | `ValidatedCommand` or validation error | Enforce command ordering and required context, such as session before moves and known player names. | Invalid commands become validation failures; impossible stream state can fail the stream. |
| `ProcessGameCommandFlow` | `ValidatedCommand` | `ProcessedCommand` or domain/application error | Apply commands to stream-local game state and produce command results. | Illegal moves are reported explicitly, not silently dropped. |
| `CreateEventEnvelopeFlow` | `ProcessedCommand` | `EventEnvelope` | Convert meaningful results into versioned event envelopes for collection or later Kafka publishing. | Missing required envelope data fails that element or the stream, depending on severity. |
| `Batch/Backpressure Flow` | `EventEnvelope` | `Seq[EventEnvelope]` or grouped output | Demonstrate bounded buffering, grouping, and Kafka-readiness. | Buffer overflow should backpressure or fail, not drop chess commands. |
| `Summary/File/Console Sink` | Envelopes or batches | `StreamSummary` / output file / console output | Collect counts, final status, errors, and emitted envelopes. | Sink write failure fails the materialized stream result. |

This document intentionally avoids full Scala implementation. The next task
should introduce small pure models and flows incrementally.

## 6. Backpressure and Strategy Plan

Pekko Streams provides backpressure through the stream runtime: downstream
demand controls upstream pulling when stages are connected normally. The first
Searchess assignment implementation should demonstrate that capability without
inventing custom `Publisher`, `Subscriber`, or `Subscription` abstractions.

Recommended first-phase strategies:

- Use bounded buffers where a boundary needs explicit capacity.
- Use a fail strategy for fatal invalid stream state.
- Use batching/grouping to show how the pipeline can prepare events for Kafka.
- Optionally use throttle in demo mode to make stream behavior visible during a
  presentation.

`DropNewest`, `DropOldest`, `Latest`, and fan-out subscriber strategies are not
needed for the first assignment pipeline. Chess move commands, resignations, and
game results must not be silently dropped. Lossy strategies may make sense later
for UI refresh signals or progress updates, but not for the command stream that
drives game state.

## 7. Kafka Future Compatibility

Current assignment shape:

```text
File Source[String]
  -> same parsing/validation/processing/envelope flows
  -> File/Console/Summary Sink
```

Later Kafka shape:

```text
Kafka Source[String]
  -> same parsing/validation/processing/envelope flows
  -> Kafka Sink[EventEnvelope]
```

Kafka should be introduced as a source/sink adapter later, not by rewriting the
core flows. The flow input can remain raw command text, and the flow output can
remain event envelopes.

Conceptual `EventEnvelope` fields:

| Field | Purpose |
|---|---|
| `eventId` | Unique event identifier, suitable for idempotency. |
| `eventType` | Stable type such as `searchess.dsl.move.applied.v1`. |
| `sessionId` | DSL/session identifier. |
| `gameId` | Game identifier when available. |
| `occurredAt` | Event creation timestamp. |
| `version` | Envelope or event schema version. |
| `payload` | Event-specific data, such as move, player, status, or result. |

The production `docs/contracts/game-events-v1.md` file already shows how
Searchess thinks about stable event names and versions. The assignment envelope
can borrow that discipline without modifying production event contracts.

## 8. Clean Architecture Boundaries

The domain model should not depend on Pekko Streams. Pekko-specific imports,
materialization, source/sink adapters, and runtime wiring should stay in
`apps/chess-streaming`.

Parsing, validation, and game-processing concepts should be pure and testable.
That means future work should prefer small data models such as `DslCommand`,
`ValidatedCommand`, `ProcessedCommand`, `EventEnvelope`, and `StreamSummary`
before wiring them into Pekko stages.

Production `EventPublisher`, `FanOutEventPublisher`, WebSocket publishers,
Redis history publishers, and Game Service event assembly should not be
modified for this assignment foundation. The existing architecture docs already
mark broker abstractions and delivery-semantics changes as non-goals for the
production eventing cleanup.

## 9. Implementation Roadmap

Task 2: Introduce DSL model and parser flow.

Task 3: Add validation flow.

Task 4: Add game-processing flow.

Task 5: Add event-envelope flow.

Task 6: Add sink and stream summary.

Task 7: Add backpressure/batch demonstration.

Task 8: Add tests.

Task 9: Prepare Kafka adapter later.

Each task should keep production Game Service eventing untouched unless a later
assignment explicitly changes scope.

## 10. Risks and Tradeoffs

The main risk is staying too close to a toy demo. The current
`ChessStreamingMain` already satisfies the broad Source/Flow/Sink shape, but it
uses an in-memory list and stream-local chess models. A Searchess DSL file makes
the assignment more realistic without changing production services.

Another risk is overengineering. This assignment does not need a custom
Reactive Streams contract, a full production event bus, WebSocket fan-out,
persistence subscribers, analytics subscribers, or Kafka dependencies. Pekko
Streams already supplies the stream runtime needed for the university task.

Move commands must not be dropped. Bounded buffers, batching, throttling, and
failure behavior are appropriate demonstrations. Lossy overflow strategies
should wait for later UI/progress use cases.

Kafka should remain a future adapter. The core design win is that a file source
and Kafka source can feed the same flows, and a console/file sink and Kafka sink
can consume the same event envelopes.
