# Game Service Ownership Audit

## Purpose

This audit records the current ownership shape after moving the runnable Game
Service app to `apps/game-service`, introducing `modules/game-contract`, and
renaming the old generic application module to `gameCore`.

It is intentionally conservative: packages such as `chess.application.*` remain
in place for now, and no runtime behavior or public contract changed.

## Current Inventory

### Apps

| App | Current role | Ownership assessment |
|---|---|---|
| `apps/game-service` | Runnable Game Service process: config load, HTTP server, health route, CORS, AI wiring, Game Service event/outbox/WebSocket runtime, app composition | Game Service-owned runtime code |
| `apps/startup-shared` | Shared config loading, persistence selection, neutral core application assembly, observable game bridge | Shared bootstrap helper; no longer owns Game Service event runtime |
| `apps/history-service` | Runnable History process: event ingestion, archive fetch, History SQLite storage | History-owned runtime code |
| `apps/desktop-gui`, `apps/tui-cli`, `apps/web-ui` | User interface apps/adapters | UI-owned app/runtime code |

### Modules

| Module | Current role | Ownership assessment |
|---|---|---|
| `modules/domain` | Chess rules, model, legal move/application logic | Shared library; should remain shared |
| `modules/notation` | FEN/PGN parsing/export and notation facade | Shared library; should remain shared for now |
| `modules/game-contract` | Game Service contract-facing IDs, session metadata, game/archive snapshot values | Shared contract module for Game and History |
| `apps/game-service/modules/core` | Game/session orchestration, `GameServiceApi`, active play commands, AI turn service, repository/event ports, archive snapshot query | Game Service-owned core module |
| `apps/game-service/modules/persistence` | In-memory and SQLite stores for active sessions/game state | Game Service-owned persistence adapter; currently generic and reused by tests |
| `apps/game-service/modules/rest-http4s` | Game/session/archive HTTP routes over `GameServiceApi` | Game Service-owned inbound adapter |
| `modules/adapter-rest-contract` | REST DTOs/mappers for Game Service routes and archive snapshot payloads | Mostly Game Service REST contract code; some DTOs may remain shared as generated/client-facing contracts |
| `apps/game-service/modules/ai` | Local deterministic AI and remote internal AI Service client | Game Service-owned outbound adapter for AI proposal |
| `apps/game-service/modules/eventing`, `apps/game-service/modules/history-delivery` | Event publisher utilities and History outbox/forwarding bridge | Game Service-owned runtime/delivery infrastructure |
| `apps/game-service/modules/websocket` | WebSocket server/event publisher for live Game events | Game Service-owned realtime adapter for now |
| `apps/history-service/modules/core` | History ingestion, remote Game archive client, archive materialization, History SQLite store | History-owned service library; depends on `game-contract`, not Game core |
| `apps/desktop-gui/modules/gui`, `apps/tui-cli/modules/tui` | Local UI adapters | UI-owned; should not become part of a standalone Game Service runtime |

## Ownership Buckets

### 1. Game Service-Owned Runtime Code

These pieces should be treated as Game Service implementation even if they are
not all physically under `apps/game-service`:

- `apps/game-service`: `ServerMain`, `ServerWiring`, `ServerRuntime`,
  `HealthRoutes`, `CorsMiddleware`, Game Service `EventAssembly`.
- `apps/game-service/modules/core`: `GameServiceApi`, `DefaultGameService`,
  `SessionGameService`, `SessionService`, session lifecycle policies, AI turn
  orchestration, active game/session query models.
- `apps/game-service/modules/persistence`: active game/session repositories and combined
  store, including SQLite.
- `apps/game-service/modules/rest-http4s`: Game Service HTTP route implementation.
- `apps/game-service/modules/ai`: AI provider adapters used by Game Service.
- `apps/game-service/modules/websocket`: live Game event push path.
- `apps/game-service/modules/eventing` and
  `apps/game-service/modules/history-delivery`: event publication fan-out and
  durable History forwarding.

### 2. Shared Library Code That Can Remain Shared

These are not service ownership problems today:

- `modules/domain`: pure chess rules and state.
- `modules/notation`: FEN/PGN support, as long as services use it as a pure
  library rather than as an archive owner.
- `modules/game-contract`: the intentionally small shared boundary used by
  Game Service and downstream services.
- Stable JSON/wire-contract documentation under `docs/contracts`.
- HTTP DTOs where they are intentionally public and stable.

### 3. Suspicious Cross-Service Coupling

The most important previous compile-time coupling has been cut:

- `apps/history-service/modules/core` no longer depends on Game core.
- History reads terminal event JSON and archive snapshot contract values through
  `modules/game-contract`.
- History still uses legacy package names such as
  `chess.application.query.game` for some contract values, but those classes
  now come from `game-contract`, not Game core.
- History tests still depend on Game-owned persistence and delivery fixtures
  fixtures. That is acceptable short term, but it reinforces the monolith test
  shape.

Other coupling to watch:

- `apps/startup-shared` still builds the neutral `DefaultGameService` core
  context for GUI/TUI and server apps, but Game Service event runtime assembly
  has moved under `apps/game-service`.
- `adapter-rest-contract` no longer depends on `game-core`; internal REST
  mapping lives in the Game-owned HTTP adapter.
- Event serialization and Game-to-History delivery have been split by ownership.
- UI adapters still compile against the same in-process Game core. That is fine
  for local apps, but not a future microservice client model.

### 4. Premature Service-Sharing

These modules look shared mostly because the monolith still owns every app:

- `apps/game-service/modules/persistence`: should remain Game Service-internal
  persistence. Other services must not import it or read its tables.
- `apps/game-service/modules/rest-http4s`: should remain clearly Game Service REST
  adapter code, not a generic backend REST adapter.
- `apps/startup-shared`: should stay narrow. It now helps GUI/TUI/server reuse
  only for neutral config, persistence selection, and core service construction.
- Event serialization stays in the shared event contract module; delivery
  mechanisms stay in Game-owned modules.

## Extraction Blockers Caused By Ownership

1. Generic Game core module identity. (Resolved at the SBT/module level.)

   The old `modules/application` source tree is now `apps/game-service/modules/core`, and
   the SBT project is `gameCore`. This makes the module graph show that Game
   orchestration is Game-owned rather than shared application infrastructure.

2. Package names still say `chess.application.*`.

   This is intentionally deferred. The module ownership is now clearer without
   a large package rename; package cleanup can happen later as a mechanical,
   separately verified slice.

3. Active persistence is in a generic adapter module.

   The adapter is correctly behind ports, but the module name does not express
   that the tables and repository implementations are Game Service-owned.

4. Startup composition is still partly shared across local apps.

   `startup-shared` creates neutral Game application objects for server, GUI,
   and TUI flows. Game Service-specific runtime assembly now lives in
   `apps/game-service`, but shared core construction remains for local UI
   compatibility.

5. Boundary contracts are mixed with internal mappers.

   `adapter-rest-contract` is useful, but because it depends on `game-core`, it
   is not yet a clean standalone contract package.

## Recommended Near-Term Boundaries

- Treat `apps/game-service` plus `apps/game-service/modules` as the current
  Game Service implementation set.
- Treat `modules/game-contract` as the small shared Game boundary module.
- Treat `modules/domain` and `modules/notation` as pure shared libraries.
- Treat `apps/history-service` plus `apps/history-service/modules` as
  History-owned.
- Keep UI apps in-process for now, but mark them as local clients of Game
  internals rather than evidence that Game core is shared.

## What Should Stay Shared For Now

- Domain rules/model.
- Notation import/export.
- `modules/game-contract` contract values.
- Published event contract documentation and JSON schema.
- HTTP DTOs where they are intentionally public and stable.

## What Should Become Game Service-Internal Later

- `DefaultGameService`, `GameServiceApi`, session command services, AI turn
  orchestration, and active query models.
- Active session/game repository implementations and SQLite schema.
- Game REST route implementation and Game WebSocket event push.
- History HTTP event forwarder.

## Next Three Extraction-Oriented Steps

1. Rename the legacy packages in `modules/game-contract` away from
   `chess.application.*` once the current low-churn seams have settled.

2. Rename service-owned packages where practical, so source package names catch
   up with the new service-oriented directory layout.

3. Decide whether `startup-shared` remains useful once GUI/TUI move toward
   HTTP clients instead of in-process Game core users.

## Conclusion

The Game Service now has a clearer ownership boundary in the module graph:
runtime code and orchestration live under `apps/game-service`, and cross-service
values live under `modules/game-contract`.

The remaining ambiguity is mostly package naming and stable SBT project IDs, not
a History-to-Game-core compile-time dependency.
