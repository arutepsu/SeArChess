# Reactive Streams Assignment Architecture

The `apps/chess-streaming` module contains the university assignment pipeline for Searchess-specific reactive streams. It is intentionally local to the assignment module and does not change the production `EventPublisher` implementation or the domain model.

## Pipeline

```text
Source[String]
  -> ParseDslFlow
  -> ValidateCommandFlow
  -> ProcessGameFlow
  -> EventEnvelopeFlow
  -> Batch/Backpressure Flow
  -> Sink
```

## Source: Searchess DSL file

The stream starts from a Searchess DSL file. The default demo input is `apps/chess-streaming/src/main/resources/searchess-game.dsl`, and the command-line runner can also accept a file path from `args`.

Each line is emitted as one `String` element. Empty lines and comment lines beginning with `#` are intentionally handled by the parser flow, so the source stays simple and only owns file reading.

## Flow 1: ParseDslFlow

`ParseDslFlow` turns raw text lines into streaming-local `DslCommand` values:

- `session <sessionId>`
- `players <whiteName> <blackName>`
- `move <playerName> <uciMove>`
- `status`
- `resign <playerName>`

Malformed input is represented as `DslParseError` data with the line number and raw input. Parse errors do not fail the stream by default, which lets the sink summarize both successful commands and rejected input.

## Flow 2: ValidateCommandFlow

`ValidateCommandFlow` checks stream-level command ordering and syntax assumptions before game processing:

- a session must be declared first
- players must be declared after the session
- moves, status, and resign commands require registered players
- a player cannot resign before players are known

Validation failures become `ValidationFailed` results. They continue through the stream as data so the assignment pipeline can report them at the sink.

## Flow 3: ProcessGameFlow

`ProcessGameFlow` owns assignment-local orchestration state:

- current session id
- registered white and black player names
- current domain `GameState`
- finished flag after resignation, checkmate, or draw

Move processing delegates chess legality to the existing domain `GameStateRules` boundary. This keeps rules in the domain layer and keeps Pekko-specific flow types out of the domain.

## Flow 4: EventEnvelopeFlow

`EventEnvelopeFlow` wraps each processing result into an `EventEnvelope`. The envelope shape is Kafka-ready: it contains an event id, event type, session id, sequence number, timestamp, and payload text. It is still only an in-memory assignment representation.

## Batch and backpressure flow

The batching flow groups envelopes before they reach the sink. This demonstrates a concrete backpressure boundary: downstream sinks receive bounded batches rather than an unbounded event firehose.

## Sink

The assignment runner provides console output and a summary sink. The sink prints each batch and collects a `StreamSummary` containing total events, accepted moves, rejected moves, parse failures, validation failures, and finished games.

## Kafka future work

Kafka is future work and is not part of this PR. The intended next step is to replace the file source with a Kafka source, or replace the console/file sink with a Kafka sink, while keeping the parse, validation, processing, envelope, and batching flows intact.

The production `EventPublisher` remains untouched. This assignment pipeline is a learning module that prepares the stream shape for a later Kafka lecture without introducing production fan-out, persistence subscribers, WebSocket subscribers, analytics subscribers, bot subscribers, or a custom Publisher/Subscriber framework.
