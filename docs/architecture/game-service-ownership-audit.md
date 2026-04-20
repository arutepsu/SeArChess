# Game Service Ownership Audit

## Purpose

This audit records the current ownership shape after moving the runnable Game
Service app to `apps/game-service`. It is intentionally conservative: it does
not propose a package rename, repository split, broker, or broad module move.
The goal is to make the next extraction work visible.

## Current Inventory

### Apps

| App | Current role | Ownership assessment |
|---|---|---|
| `apps/game-service` | Runnable Game Service process: config load, HTTP server, health route, CORS, AI wiring, app composition | Game Service-owned runtime code |
| `apps/startup-shared` | Shared assembly for persistence, event fan-out, core application context, observable game bridge | Monolith-oriented shared startup; partly Game Service-owned, partly GUI/TUI compatibility |
| `apps/history-service` | Runnable History process: event ingestion, archive fetch, History SQLite storage | History-owned runtime code |
| `apps/desktop-gui`, `apps/tui-cli`, `apps/web-ui` | User interface apps/adapters | UI-owned app/runtime code |

### Modules

| Module | Current role | Ownership assessment |
|---|---|---|
| `modules/domain` | Chess rules, model, legal move/application logic | Shared library; should remain shared |
| `modules/notation` | FEN/PGN parsing/export and notation facade | Shared library; should remain shared for now |
| `modules/game-contract` | Game Service contract-facing IDs, session metadata, game/archive snapshot values | Shared contract module for Game and History |
| `modules/application` | Game/session orchestration, `GameServiceApi`, active play commands, AI turn service, repository/event ports, archive snapshot query | Mostly Game Service-owned application code, currently named as a shared monolith layer |
| `modules/adapter-persistence` | In-memory and SQLite stores for active sessions/game state | Game Service-owned persistence adapter; currently generic and reused by tests |
| `modules/adapter-rest-http4s` | Game/session/archive HTTP routes over `GameServiceApi` | Game Service-owned inbound adapter |
| `modules/adapter-rest-contract` | REST DTOs/mappers for Game Service routes and archive snapshot payloads | Mostly Game Service REST contract code; some DTOs may remain shared as generated/client-facing contracts |
| `modules/adapter-ai` | Local deterministic AI and remote Python AI client | Game Service-owned outbound adapter for AI proposal |
| `modules/adapter-event` | Event serializer, fan-out/no-op publishers, History HTTP forwarding bridge | Mixed: Game event wire contract may be shared; History HTTP forwarder is Game Service-owned outbound adapter |
| `modules/adapter-websocket` | WebSocket server/event publisher for live Game events | Game Service-owned realtime adapter for now |
| `modules/history` | History ingestion, remote Game archive client, archive materialization, History SQLite store | History-owned service library, but currently imports Game application types |
| `modules/adapter-gui`, `modules/adapter-tui` | Local UI adapters | UI-owned; should not become part of a standalone Game Service runtime |

## Ownership Buckets

### 1. Game Service-Owned Runtime Code

These pieces should be treated as Game Service implementation even if they are
not all physically under `apps/game-service` yet:

- `apps/game-service`: `ServerMain`, `ServerWiring`, `ServerRuntime`,
  `HealthRoutes`, `CorsMiddleware`.
- `modules/application`: `GameServiceApi`, `DefaultGameService`,
  `SessionGameService`, `SessionService`, session lifecycle policies, AI turn
  orchestration, active game/session query models.
- `modules/adapter-persistence`: active game/session repositories and combined
  store, including SQLite.
- `modules/adapter-rest-http4s`: Game Service HTTP route implementation.
- `modules/adapter-ai`: AI provider adapters used by Game Service.
- `modules/adapter-websocket`: live Game event push path.
- The Game-owned portion of `modules/adapter-event`: event publication fan-out,
  `AppEventSerializer`, and the best-effort History HTTP bridge.

### 2. Shared Library Code That Can Remain Shared

These are not service ownership problems today:

- `modules/domain`: pure chess rules and state.
- `modules/notation`: FEN/PGN support, as long as services use it as a pure
  library rather than as an archive owner.
- Stable JSON/wire-contract documentation under `docs/contracts`.
- Small DTO/contract shapes may remain shared while the repo is single-source,
  but they should be treated as published contracts, not internal models.

### 3. Suspicious Cross-Service Coupling

The biggest previous coupling was History depending on Game Service internals:

- `modules/history` used to depend on `modules/application` at compile time.
  That seam is now cut by `modules/game-contract`.
- History still uses legacy package names such as `chess.application.query.game`
  for contract values, but those classes are now compiled from the contract
  module rather than the Game Service application module.
- History tests depend on `adapter-persistence` and `adapter-event` fixtures.
  That is acceptable short term, but it reinforces the monolith test shape.

Other coupling to watch:

- `apps/startup-shared` builds `DefaultGameService` and selects active
  persistence/event infrastructure for GUI/TUI and server apps. This keeps old
  local app flows working, but it means Game Service composition is still not
  fully owned by `apps/game-service`.
- `adapter-rest-contract` depends on `application`, so external REST DTOs are
  currently mapped from internal Game application models in the same repo.
- `adapter-event` combines shared event serialization with a concrete
  Game-to-History HTTP forwarder. The serializer is boundary code; the forwarder
  is Game Service runtime integration.
- UI adapters still compile against the same in-process Game application layer.
  That is fine for local apps, but not a future microservice client model.

### 4. Premature Service-Sharing

These modules look shared mostly because the monolith still owns every app:

- `modules/application`: should eventually be `game-application` or equivalent.
  It is not a general shared application layer; it is the Game Service core.
- `modules/adapter-persistence`: should eventually be Game Service-internal
  persistence. Other services must not import it or read its tables.
- `modules/adapter-rest-http4s`: should eventually be clearly Game Service REST
  adapter code, not a generic backend REST adapter.
- `apps/startup-shared`: should shrink or split. Shared app assembly currently
  helps GUI/TUI/server reuse, but it blurs service ownership.
- Parts of `adapter-event`: event serializer can be a shared/published contract;
  delivery mechanisms should be service-owned adapters.

## Extraction Blockers Caused By Ownership

1. History imports `modules/application`. (Resolved for main code.)

   A separately versioned History Service should depend on Game event JSON and
   archive HTTP DTOs, not on the Game Service application module. The direct
   main dependency is now removed; the remaining cleanup is package naming and
   test-fixture decoupling.

2. Game Service core is still named and packaged as generic `application`.

   The code is logically Game-owned, but the module name suggests it is safe for
   other services to import. That will become misleading as more services appear.

3. Active persistence is in a generic adapter module.

   The adapter is correctly behind ports, but the module name does not express
   that the tables and repository implementations are Game Service-owned.

4. Startup composition is still shared across local apps.

   `startup-shared` creates Game Service application objects for server, GUI,
   and TUI flows. This preserves behavior, but a standalone Game Service should
   eventually own its composition root without GUI/TUI concerns.

5. Boundary contracts are mixed with internal mappers.

   `adapter-rest-contract` is useful, but because it depends on `application`,
   it is not yet a clean standalone contract package.

## Recommended Near-Term Boundaries

- Treat `apps/game-service` plus `modules/application`,
  `adapter-rest-http4s`, `adapter-persistence`, `adapter-ai`,
  `adapter-websocket`, and Game-owned event adapters as the current Game
  Service implementation set.
- Treat `modules/domain` and `modules/notation` as pure shared libraries.
- Treat `modules/history` and `apps/history-service` as History-owned.
- Keep the explicit `modules/game-contract` seam small: Game event JSON plus
  archive snapshot values should be consumable without depending on
  `modules/application`.
- Keep UI apps in-process for now, but mark them as local clients of Game
  internals rather than evidence that the Game application module is shared.

## What Should Stay Shared For Now

- Domain rules/model.
- Notation import/export.
- Published event contract documentation and JSON schema.
- HTTP DTOs where they are intentionally public and stable.

## What Should Become Game Service-Internal Later

- `DefaultGameService`, `GameServiceApi`, session command services, AI turn
  orchestration, and active query models.
- Active session/game repository implementations and SQLite schema.
- Game REST route implementation and Game WebSocket event push.
- History HTTP event forwarder.

## Next Three Extraction-Oriented Steps

1. Rename the legacy contract packages in `modules/game-contract` away from
   `chess.application.*` once the current low-churn seam has settled.

2. Rename or split `modules/application` into a clearly Game-owned module
   shape, such as `modules/game-application`, after the contract seam exists.
   Preserve packages initially if needed to keep churn low.

3. Move Game-owned adapters toward explicit Game naming or ownership, starting
   with active persistence and REST route modules. Do this one module at a time,
   with no route or storage behavior changes.

## Conclusion

The Game Service has a clearer app boundary after the relocation, but ownership
is still partly implicit. The next real extraction issue is not runtime startup;
it is compile-time coupling: History should no longer need the Game Service
application module to understand archive trigger data or archive HTTP payloads.
