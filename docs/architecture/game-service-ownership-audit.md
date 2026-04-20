# Game Service Ownership Audit

## Purpose

This audit records the current ownership shape after moving the runnable Game
Service app to `apps/game-service`, introducing `modules/game-contract`, and
renaming the old generic application module to `modules/game-core`.

It is intentionally conservative: packages such as `chess.application.*` remain
in place for now, and no runtime behavior or public contract changed.

## Current Inventory

### Apps

| App | Current role | Ownership assessment |
|---|---|---|
| `apps/game-service` | Runnable Game Service process: config load, HTTP server, health route, CORS, AI wiring, app composition | Game Service-owned runtime code |
| `apps/startup-shared` | Shared assembly for persistence, event fan-out, core Game context, observable game bridge | Monolith-oriented shared startup; partly Game Service-owned, partly GUI/TUI compatibility |
| `apps/history-service` | Runnable History process: event ingestion, archive fetch, History SQLite storage | History-owned runtime code |
| `apps/desktop-gui`, `apps/tui-cli`, `apps/web-ui` | User interface apps/adapters | UI-owned app/runtime code |

### Modules

| Module | Current role | Ownership assessment |
|---|---|---|
| `modules/domain` | Chess rules, model, legal move/application logic | Shared library; should remain shared |
| `modules/notation` | FEN/PGN parsing/export and notation facade | Shared library; should remain shared for now |
| `modules/game-contract` | Game Service contract-facing IDs, session metadata, game/archive snapshot values | Shared contract module for Game and History |
| `modules/game-core` | Game/session orchestration, `GameServiceApi`, active play commands, AI turn service, repository/event ports, archive snapshot query | Game Service-owned core module |
| `modules/adapter-persistence` | In-memory and SQLite stores for active sessions/game state | Game Service-owned persistence adapter; currently generic and reused by tests |
| `modules/adapter-rest-http4s` | Game/session/archive HTTP routes over `GameServiceApi` | Game Service-owned inbound adapter |
| `modules/adapter-rest-contract` | REST DTOs/mappers for Game Service routes and archive snapshot payloads | Mostly Game Service REST contract code; some DTOs may remain shared as generated/client-facing contracts |
| `modules/adapter-ai` | Local deterministic AI and remote Python AI client | Game Service-owned outbound adapter for AI proposal |
| `modules/adapter-event` | Event serializer, fan-out/no-op publishers, History HTTP forwarding bridge | Mixed: Game event wire contract may be shared; History HTTP forwarder is Game Service-owned outbound adapter |
| `modules/adapter-websocket` | WebSocket server/event publisher for live Game events | Game Service-owned realtime adapter for now |
| `modules/history` | History ingestion, remote Game archive client, archive materialization, History SQLite store | History-owned service library; depends on `game-contract`, not Game core |
| `modules/adapter-gui`, `modules/adapter-tui` | Local UI adapters | UI-owned; should not become part of a standalone Game Service runtime |

## Ownership Buckets

### 1. Game Service-Owned Runtime Code

These pieces should be treated as Game Service implementation even if they are
not all physically under `apps/game-service`:

- `apps/game-service`: `ServerMain`, `ServerWiring`, `ServerRuntime`,
  `HealthRoutes`, `CorsMiddleware`.
- `modules/game-core`: `GameServiceApi`, `DefaultGameService`,
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
- `modules/game-contract`: the intentionally small shared boundary used by
  Game Service and downstream services.
- Stable JSON/wire-contract documentation under `docs/contracts`.
- HTTP DTOs where they are intentionally public and stable.

### 3. Suspicious Cross-Service Coupling

The most important previous compile-time coupling has been cut:

- `modules/history` no longer depends on Game core.
- History reads terminal event JSON and archive snapshot contract values through
  `modules/game-contract`.
- History still uses legacy package names such as
  `chess.application.query.game` for some contract values, but those classes
  now come from `game-contract`, not Game core.
- History tests still depend on `adapter-persistence` and `adapter-event`
  fixtures. That is acceptable short term, but it reinforces the monolith test
  shape.

Other coupling to watch:

- `apps/startup-shared` builds `DefaultGameService` and selects active
  persistence/event infrastructure for GUI/TUI and server apps. This keeps old
  local app flows working, but it means Game Service composition is still not
  fully owned by `apps/game-service`.
- `adapter-rest-contract` depends on `game-core`, so external REST DTOs are
  still mapped from internal Game core models in the same repo.
- `adapter-event` combines shared event serialization with a concrete
  Game-to-History HTTP forwarder. The serializer is boundary code; the forwarder
  is Game Service runtime integration.
- UI adapters still compile against the same in-process Game core. That is fine
  for local apps, but not a future microservice client model.

### 4. Premature Service-Sharing

These modules look shared mostly because the monolith still owns every app:

- `modules/adapter-persistence`: should eventually be Game Service-internal
  persistence. Other services must not import it or read its tables.
- `modules/adapter-rest-http4s`: should eventually be clearly Game Service REST
  adapter code, not a generic backend REST adapter.
- `apps/startup-shared`: should shrink or split. Shared app assembly currently
  helps GUI/TUI/server reuse, but it blurs service ownership.
- Parts of `adapter-event`: event serializer can be a shared/published contract;
  delivery mechanisms should be service-owned adapters.

## Extraction Blockers Caused By Ownership

1. Generic Game core module identity. (Resolved at the SBT/module level.)

   The old `modules/application` source tree is now `modules/game-core`, and
   the SBT project is `gameCore`. This makes the module graph show that Game
   orchestration is Game-owned rather than shared application infrastructure.

2. Package names still say `chess.application.*`.

   This is intentionally deferred. The module ownership is now clearer without
   a large package rename; package cleanup can happen later as a mechanical,
   separately verified slice.

3. Active persistence is in a generic adapter module.

   The adapter is correctly behind ports, but the module name does not express
   that the tables and repository implementations are Game Service-owned.

4. Startup composition is still shared across local apps.

   `startup-shared` creates Game Service application objects for server, GUI,
   and TUI flows. This preserves behavior, but a standalone Game Service should
   eventually own its composition root without GUI/TUI concerns.

5. Boundary contracts are mixed with internal mappers.

   `adapter-rest-contract` is useful, but because it depends on `game-core`, it
   is not yet a clean standalone contract package.

## Recommended Near-Term Boundaries

- Treat `apps/game-service` plus `modules/game-core`,
  `adapter-rest-http4s`, `adapter-persistence`, `adapter-ai`,
  `adapter-websocket`, and Game-owned event adapters as the current Game
  Service implementation set.
- Treat `modules/game-contract` as the small shared Game boundary module.
- Treat `modules/domain` and `modules/notation` as pure shared libraries.
- Treat `modules/history` and `apps/history-service` as History-owned.
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

2. Decouple `adapter-rest-contract` from `game-core` where practical, so REST
   DTOs can be treated as a cleaner published Game Service contract.

3. Move Game-owned adapters toward explicit Game naming or ownership, starting
   with active persistence and REST route modules. Do this one module at a time,
   with no route or storage behavior changes.

## Conclusion

The Game Service now has a clearer ownership boundary in the module graph:
runtime code lives under `apps/game-service`, orchestration lives under
`modules/game-core`, and cross-service values live under `modules/game-contract`.

The remaining ambiguity is mostly package naming and generic adapter naming,
not a History-to-Game-core compile-time dependency.
