# Future System Architecture

This note describes the target service architecture implied by the current
modular monolith. It is a design guide, not an implementation plan for immediate
service extraction.

The priority is ownership clarity: each future service should own its own data,
contracts, and operational concerns. Services should not share database tables
or reach into each other's internals.

## Candidate Services

### Game Service

Owns active gameplay:

- active games and current board state
- session lifecycle for active play
- move submission and legal state transitions
- turn ownership and controller policy
- active-game/session persistence
- publication of game/session lifecycle events
- AI move orchestration through an AI port

Game Service remains authoritative. Even when an external AI proposes a move,
Game Service validates, applies, persists, and emits the resulting events.

Likely synchronous API:

- create session/game
- submit move
- resign/cancel
- request AI move
- get current game/session state
- get legal moves
- future archive/export snapshot for finished or closed games

Likely events:

- `game.session.created`
- `game.move.accepted`
- `game.move.rejected`
- `game.finished`
- `game.resigned`
- `game.session.cancelled`
- AI monitoring events

Data ownership:

- active session table/store
- active game-state table/store
- optional active read projection/cache

### AI Service

Owns engine/model execution, not chess truth:

- engine selection and configuration
- model process lifecycle
- search depth/time controls
- move suggestion
- engine health and diagnostics

It should not persist active game state or apply moves. It receives a position or
game snapshot, returns a candidate move, and lets Game Service decide whether the
move is legal and applicable.

Likely synchronous API:

- suggest move for a position/snapshot
- list engines/capabilities
- health/status

Data ownership:

- engine configuration
- model metadata/cache
- optional request diagnostics

### History / Notation Service

Owns long-term completed/closed-game records:

- durable archives
- replay-oriented views
- PGN/FEN export documents with metadata
- search indexes
- historical analytics datasets

It should not own active move validation or active session lifecycle.

Recommended integration is hybrid:

- events trigger archival work
- a Game Service pull/export contract materializes the full archive snapshot

Data ownership:

- archive records
- notation documents
- replay/search projections
- historical indexes

### Tournament Service

Owns competition structure:

- tournament registration
- brackets, rounds, pairings
- standings and tiebreaks
- tournament-specific result policy
- scheduling game creation through Game Service

It should not apply moves or inspect Game Service persistence tables directly.
It requests games and consumes result events.

Data ownership:

- tournaments
- players/entries in tournament context
- rounds, pairings, standings
- result adjudication metadata

### Bot / Integration Service

Owns platform-specific behavior:

- Slack/Discord/chat commands
- external account mapping
- platform notifications
- command translation to Game Service API calls
- platform rate limits and retries

It should not own chess state, AI internals, tournament truth, or archive
storage.

Data ownership:

- platform workspace/guild/channel mappings
- external user mappings
- integration tokens/configuration
- delivery logs

### API Gateway / Edge

Owns external ingress concerns:

- routing to services
- authentication and authorization enforcement
- TLS/CORS/rate limiting
- request correlation IDs
- public API versioning
- possibly WebSocket/SSE edge fanout

It should not contain chess business logic. It may aggregate responses only when
that avoids pushing edge concerns into core services.

Data ownership:

- usually none beyond configuration, auth/session cache, and operational logs

### Event / Broker Role

The broker is infrastructure, not a business service. It carries facts between
services.

Recommended role:

- durable publication of Game Service events
- fanout to History, Tournament, Bot, Notification, and Analytics consumers
- replay for rebuilding projections
- consumer isolation

Do not introduce broker semantics until event payloads, occurrence timestamps,
versioning, and retry/outbox expectations are deliberately designed.

Data ownership:

- event log/topics as infrastructure
- not business tables

## Interaction View

### Create / Play / Finish A Game

```text
Client
  -> API Gateway
  -> Game Service: create session/game
  -> Game Service DB: write active session + game state
  -> Broker/WebSocket: game.session.created

Client
  -> API Gateway
  -> Game Service: submit move
  -> Game Service: validate against domain rules
  -> Game Service DB: write updated session + game state
  -> Broker/WebSocket: game.move.accepted

If terminal:
  -> Broker/WebSocket: game.finished or game.resigned
```

The client reads current state from Game Service. Realtime transports are
notifications, not the canonical state source.

### AI Move Request

```text
Client or scheduler
  -> API Gateway
  -> Game Service: request AI move
  -> Game Service: verify current side is AI-controlled
  -> AI Service: suggest move
  -> Game Service: validate/apply returned move
  -> Game Service DB: write updated active state
  -> Broker/WebSocket: game.move.accepted
  -> Broker/WebSocket: game.ai_move.applied or game.ai_move.failed
```

AI Service proposes. Game Service decides.

### Archive Finished Game

```text
Game Service
  -> Broker: game.finished / game.resigned / game.session.cancelled

History / Notation Service
  -> consumes event trigger
  -> Game Service: fetch archive/export snapshot
  -> Notation capability: derive PGN/FEN/export documents
  -> History DB/Search Index: persist archive and projections
```

Cancellation is archived as a closed/cancelled session, not as a normal chess
result.

### Tournament Orchestrating Games

```text
Tournament Service
  -> Game Service: create game/session for pairing
  -> Tournament DB: record pairing -> gameId/sessionId

Players
  -> Game Service: play game

Game Service
  -> Broker: game.finished / game.resigned / game.session.cancelled

Tournament Service
  -> consumes result event
  -> Game Service or History: fetch result/archive snapshot if needed
  -> Tournament DB: update standings and next pairings
```

Tournament Service owns standings. Game Service owns the game result facts.

## Synchronous vs Asynchronous Contracts

Use synchronous calls when the caller needs an immediate authoritative answer:

- submit a move
- create a game/session
- ask for legal moves
- request an AI move, at least initially
- fetch current active state
- fetch archive/export snapshot

Use asynchronous events when communicating facts after they happen:

- move accepted/rejected
- game finished/resigned/cancelled
- session lifecycle changed
- AI requested/applied/failed
- archive materialized
- tournament standings updated

Avoid using events as commands until durable delivery, retries, idempotency, and
correlation are designed.

## Database Ownership

Each service should own its database/schema:

- Game Service: active sessions, active game state, active read projections.
- AI Service: engine/model configuration and diagnostics.
- History / Notation Service: archives, notation documents, replay/search
  projections.
- Tournament Service: tournaments, entrants, pairings, standings.
- Bot / Integration Service: external platform mappings and delivery state.
- API Gateway: config/session cache only, if needed.
- Broker/Event infrastructure: event logs/topics, not service business tables.

Other services must not read or write Game Service active tables directly. They
consume APIs and events.

## Recommended Extraction Order

1. Keep hardening Game Service inside the modular monolith.
   - Finish application-owned game read projection.
   - Move legal-target derivation out of REST mapping.
   - Define archive/export snapshot.
   - Add event versioning/occurredAt/idempotency expectations.

2. Extract AI Service first if extraction is needed soon.
   - It has a clean port already: Game Service calls AI and remains
     authoritative.
   - It has independent scaling/runtime concerns.
   - Failure is easier to contain: no AI provider means clear capability errors.

3. Extract History / Notation Service after archive snapshot exists.
   - It is downstream and can be event-triggered.
   - It should not block active play.
   - It needs a stable materialization contract before extraction.

4. Extract Bot / Integration Service when platform complexity grows.
   - Platform tokens, retries, rate limits, and command UX are operationally
     different from core gameplay.

5. Extract Tournament Service when tournament state becomes real product scope.
   - Premature extraction would create abstractions around pairings and standings
     that do not exist yet.

6. Introduce API Gateway and durable broker when multiple deployable services
   actually exist or external ingress needs require them.

## What Should Remain In The Modular Monolith Longer

Keep these in-process until contracts are stable:

- domain rules
- Game Service application orchestration
- REST/WebSocket adapters for the single backend
- notation pure library
- in-memory/simple persistence adapters for local/runtime tests
- startup/bootstrap wiring

Avoid extracting services only because modules exist. Extract around ownership,
scaling, operational isolation, and stable contracts.

## Major Tradeoffs And Risks

Microservices too early:

- increases operational complexity before contracts are stable
- makes local development and tests slower
- can freeze immature DTO/event shapes into public contracts

Modular monolith too long:

- can hide accidental coupling
- encourages direct reuse of internals unless boundaries are enforced
- may delay operational separation for AI/runtime-heavy workloads

Event-driven history:

- good for loose coupling and replay
- requires durable broker, outbox/idempotency, event versioning, and complete
  materialization contracts

Synchronous archive pull:

- gives complete canonical data
- couples history availability to Game Service read availability

The realistic path is incremental: keep the monolith, harden boundaries, extract
AI first if operationally useful, then extract downstream consumers once their
contracts are explicit.

## First Extraction Target

The first actual extraction target should be the AI Service.

Reasons:

- The port already exists: `AIProvider`.
- Game Service already treats AI as a collaborator, not an authority.
- AI has distinct runtime/scaling needs from active gameplay.
- AI failures can be isolated behind existing `AI_NOT_CONFIGURED` /
  provider-failure semantics.
- Extraction does not require other services to consume new durable events.

History / Notation is the next likely downstream extraction, but only after the
archive/export snapshot contract is designed. Tournament and Bot/Integration
should wait until their business scopes are real enough to justify service
ownership.
