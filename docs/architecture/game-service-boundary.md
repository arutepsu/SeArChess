# Game Service Boundary

This project is still a modular monolith. The Game Service is a logical service
boundary inside that monolith, not a separately deployed process yet.

The boundary exists so active gameplay can later be extracted without first
untangling chess rules, session lifecycle, persistence, event publication, and
transport adapters from each other.

## Ownership

The Game Service owns the authoritative backend truth for active play:

- active games and their current board state
- session lifecycle around a game
- side-to-move and turn ownership checks
- move submission and legal state transitions
- active-game/session persistence through application ports
- publication of game and session lifecycle events
- AI move requests through an AI port, with final validation and application
  still performed by the Game Service

The authoritative write path is:

```text
inbound adapter
  -> GameServiceApi
  -> application orchestration / commands / AI turn service
  -> domain rules
  -> persistence port(s)
  -> event publisher
```

Adapters must not apply moves, decide winners, mutate game state, or bypass the
application boundary.

## Session vs Game

Game and Session are separate first-class concepts:

- Game = chess state and rules. The domain model owns board state, legal moves,
  move history required for active play, current player, and game status.
- Session = lifecycle/container around a game. The application session model
  owns participants/controllers, mode, lifecycle phase, timestamps, and the link
  to the active game id.

The Game Service owns both for active play, but keeps them separate so history,
tournaments, bots, and external AI can be extracted later without inheriting the
entire active-game write model.

## Inbound Interfaces

Current inbound adapters for the Game Service boundary:

- `adapter-rest-http4s`: HTTP routes for game/session commands and queries.
- `adapter-websocket`: realtime update transport fed by application events.

The application seam is `chess.application.GameServiceApi`. REST DTOs in
`adapter-rest-contract` are transport schema, not the service boundary itself.

### AI HTTP Capability

`POST /games/{gameId}/ai-move` is always part of the REST surface. Runtimes that
do not wire an AI provider return a structured `AI_NOT_CONFIGURED` error instead
of hiding the endpoint. This keeps the public API shape stable while making the
runtime capability explicit.

When AI is configured, the route still calls `GameServiceApi`; the AI provider
only proposes a move. The Game Service remains authoritative for validation,
persistence, and event publication.

### Game State Read Projection Decision

`GameServiceApi.getGameState` intentionally remains
`Either[RepositoryError, GameState]` for now.

The current HTTP game projection still depends on canonical, rule-bearing
domain state. In particular, `GameMapper.toGameResponse` derives
`legalTargetsByFrom` from `GameState` using domain move-generation rules. Because
that projection needs the complete rule context carried by `GameState`, replacing
the method immediately would either create a shallow wrapper with little boundary
benefit or force a broader game-read contract redesign before the desired public
shape has been chosen.

The stronger next boundary issue is not the return type alone; it is that
legal-target derivation currently sits in the REST contract mapping layer. A
future focused slice should define the intended application-owned game read
projection deliberately, move legal-target derivation out of REST mapping, and
then reassess whether `getGameState` should return a `GameSnapshot` or similar
application read model.

## Event Boundary

`AppEvent` is the current logical Game Service event contract. It is still an
in-process application type, not a Kafka schema, but consumers should treat the
following vocabulary as the stable boundary language for active-game lifecycle
notifications.

### Current Emitted Events

The Game Service currently emits:

- `SessionCreated`: a session and its initial active game state were persisted.
- `SessionLifecycleChanged`: a session lifecycle phase changed after a command.
- `MoveApplied`: a move was accepted, applied, and persisted.
- `MoveRejected`: a submitted move was rejected by game/domain validation.
- `PromotionPending`: a session entered the promotion-choice pause.
- `GameFinished`: a move produced a terminal checkmate or draw state.
- `GameResigned`: a player resigned and the game records the winner.
- `SessionCancelled`: a session was administratively closed without changing the
  game result.
- `AITurnRequested`: the AI-turn guard passed and an AI provider is being asked
  for a move.
- `AITurnCompleted`: an AI-proposed move was accepted through the normal Game
  Service move path.
- `AITurnFailed`: an AI provider call or AI move application failed after the
  AI turn was requested.

Events are published only after successful persistence for state transitions.
`MoveRejected` and `AITurnFailed` are audit/monitoring facts and do not imply a
game-state change.

### Boundary Vocabulary

The recommended future broker/API vocabulary maps directly from the current
internal names:

| Boundary event | Current `AppEvent` |
| --- | --- |
| `game.session.created` | `SessionCreated` |
| `game.session.lifecycle_changed` | `SessionLifecycleChanged` |
| `game.move.accepted` | `MoveApplied` |
| `game.move.rejected` | `MoveRejected` |
| `game.promotion.pending` | `PromotionPending` |
| `game.finished` | `GameFinished` |
| `game.resigned` | `GameResigned` |
| `game.session.cancelled` | `SessionCancelled` |
| `game.ai_move.requested` | `AITurnRequested` |
| `game.ai_move.applied` | `AITurnCompleted` |
| `game.ai_move.failed` | `AITurnFailed` |

The Scala names should remain stable for now to avoid subscriber churn. A future
broker adapter can translate to the dotted boundary names without forcing an
application-wide rename.

### Intended Consumers

These events are meant to support later extraction paths:

- History/Notation Service: consume `MoveApplied`, `GameFinished`,
  `GameResigned`, and `SessionCancelled` to build archives and PGN-oriented
  views.
- Tournament Service: consume `GameFinished`, `GameResigned`, and
  `SessionCancelled` to update pairings, standings, and result policy.
- Bot/Integration Service: consume session, move, and finish events to notify
  external platforms without owning game truth.
- Event/Notification Service: bridge the in-process events to WebSocket,
  broker, email, push, or other fanout mechanisms.
- Analytics: consume accepted/rejected move and lifecycle events for aggregate
  metrics.

### Payload Assessment

All current events carry both `sessionId` and `gameId`, which is the most
important routing fact for future consumers. Move events carry the move.
Lifecycle events carry both previous and next lifecycle phases. Finish/resign
events carry the result fact needed by downstream consumers.

Known limitations are intentionally deferred:

- Events do not carry an explicit occurrence timestamp. Consumers can currently
  use session `updatedAt` via queries when needed, but durable broker events
  should eventually include `occurredAt`.
- Events do not carry a sequence number or version. Consumers must not infer a
  total order across event types beyond the local publish order of a single
  command.
- `MoveApplied` does not carry the resulting full game snapshot. Consumers that
  need canonical state should query `GET /games/{gameId}` or a future game-read
  projection.
- `AITurnCompleted` means the AI move was applied by the authoritative Game
  Service. It is monitoring metadata; the state-changing fact is still
  `MoveApplied`.

### Internal vs Boundary-Relevant

Domain events remain internal chess-rule facts. `AppEvent` is the Game Service
boundary event surface. `PromotionPending` is boundary-relevant for realtime UI
and bot flows, but it is session workflow metadata rather than an archive result.
AI events are boundary-relevant for monitoring and UX, but future History or
Tournament services should rely on `MoveApplied` and result events for game
truth.

## Active-Game Persistence Boundary

`SessionGameStore` is the authoritative active-write boundary for commands that
change both session metadata and game state. `SessionGameStore.save(session,
state)` represents one logical write of:

- the session write model (`GameSession`)
- the active game-state write model (`GameState`) for `session.gameId`

The Game Service command path must use this combined store for active gameplay
commands such as creating a game/session, submitting a move, and resigning. This
keeps the write path extraction-ready: a future standalone Game Service can own
the active session and active game-state tables without exposing them to other
services.

### Current Atomicity Meaning

In the current in-memory adapter, "atomic" means both records are written through
a single application port call and callers receive one `RepositoryError` if the
combined write cannot be completed. The in-memory implementation delegates to
the session and game repositories sequentially; because those repositories do
not fail in normal operation, this is sufficient for local tests and single-JVM
runtime composition.

A durable adapter must strengthen this to real storage atomicity: both records
must be committed in one database transaction, or neither should become visible.

### Consistency Expectations

Application services expect these guarantees:

- Successful command results are returned only after the combined write
  succeeds.
- State-transition events are published only after the combined write succeeds.
- A failed combined write surfaces as a service error and publishes no
  state-transition events.
- `SessionRepository` is the session read/session-only lifecycle port.
- `GameRepository` is the active game-state read port.
- `SessionRepository.save` and `GameRepository.save` are not the authoritative
  write path for active gameplay commands when session and game state must
  change together.

### Adapter-Specific Concerns

Locking, transaction isolation, retry policy, optimistic concurrency, schema
layout, serialization format, and durability guarantees belong to persistence
adapters. The application layer only depends on the port contracts and maps
`RepositoryError` into service-level errors.

### Future Service Rule

Future History, Tournament, Bot, Analytics, and Notification services must not
share or update active Game Service persistence tables directly. They should
consume Game Service APIs and events. If a future service needs its own query or
archive model, it should own its own storage and build it from published Game
Service facts.

## Outbound Ports

The Game Service depends on application ports for all outside effects:

- `SessionGameStore`: combined authoritative write for session metadata and
  active game state.
- `SessionRepository`: session reads and session-only lifecycle updates.
- `GameRepository`: active game-state reads.
- `EventPublisher`: game/session/AI event publication.
- `AIProvider`: AI move proposal.

These ports are the extraction seams. A future standalone Game Service should
own its active persistence and publish events for other services rather than
sharing database tables directly.

## Outbound Adapters

Current outbound adapters:

- `adapter-persistence`: in-memory repository/store implementations.
- `adapter-event`: in-process event publication/fanout.
- `adapter-ai`: deterministic first-legal-move AI provider used as a real port
  adapter, not as a rule owner.

`game-service` and `startup-shared` only assemble runtime wiring. They are
not business services.

## Explicitly Out Of Scope

The Game Service does not own:

- tournament brackets, standings, pairings, or registrations
- Slack, Discord, or bot platform integration logic
- analytics and reporting
- long-term archive/search concerns
- AI engine internals, model training, or evaluation pipelines
- Kafka, durable outbox, or broker operations in this slice

Future services should consume stable APIs and events rather than reaching into
Game Service internals or sharing active-game persistence tables.

## Future Extraction Targets

Likely future services:

- AI Service: owns engine/model execution; Game Service calls it through
  `AIProvider` and still validates/applies returned moves.
- History/Notation Service: consumes finished-game events or exports and owns
  archive/search/PGN-oriented views.
- Tournament Service: owns brackets, standings, rounds, and pairings.
- Bot/Integration Service: owns Slack/Discord/platform behavior and calls Game
  Service APIs.
- Event/Notification Service: owns broker integration, subscriptions, and
  notification fanout.
