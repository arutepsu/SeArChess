# Runtime Composition Ownership

Status: local/dev extraction cleanup  
Scope: app startup and runtime wiring ownership

## Decision

`apps/game-service` owns the Game Service runtime composition root.

`apps/history-service` owns the History Service runtime composition root.

`apps/startup-shared` remains only for neutral local bootstrap helpers that are
shared by multiple app entry points and do not start service runtime
infrastructure.

## What Moved

Game Service event runtime assembly moved from `apps/startup-shared` to
`apps/game-service`:

- WebSocket server lifecycle
- History forwarding / outbox forwarder lifecycle
- terminal event JSON serializer selection for the SQLite outbox path
- Game Service event fan-out composition

These concerns are specific to the Game Service process. They are not needed by
desktop GUI or TUI runtimes.

## What Remains Shared

`apps/startup-shared` still owns:

- `AppConfig` / `ConfigLoader`: neutral environment parsing used by multiple
  app entry points.
- `PersistenceAssembly`: repository selection for local Game runtime modes.
- `CoreAssembly`: construction of application services from persistence plus
  neutral event bindings.
- `ObservableGame`: local GUI/TUI notification bridge.

`CoreAssembly` now accepts `CoreEventBindings`, a small neutral value containing
only:

- application `EventPublisher`
- optional terminal event serializer

It does not know about WebSocket servers, History forwarder shutdown, HTTP
routes, or outbox inspection.

## Ownership Boundaries

| Area | Owner |
|---|---|
| Game HTTP server startup | `apps/game-service` |
| Game WebSocket runtime | `apps/game-service` |
| Game -> History outbox draining | `apps/game-service` |
| Game active state/session persistence selection | shared bootstrap helper for now |
| History HTTP server startup | `apps/history-service` |
| History archive persistence | `apps/history-service` / `apps/history-service/modules/core` |
| GUI/TUI local runtime | `apps/desktop-gui`, `apps/tui-cli` |

## Why This Is Enough For This Slice

The main ownership smell was that GUI/TUI depended on a shared startup module
that also contained Game Service event runtime wiring. After this cleanup,
`startup-shared` no longer depends on `adapter-websocket` or `adapter-event`.
Those dependencies are pulled by `apps/game-service`, where the service runtime
is actually assembled.

This is intentionally not a large module split. Persistence and core assembly
remain shared because GUI/TUI still construct local Game application services.
That shared code is neutral enough for the current extraction stage.

## Deferred

- Rename `startup-shared` to something narrower, such as `local-bootstrap`, if
  it continues to shrink.
- Split GUI/TUI local app bootstrap from Game Service bootstrap if the desktop
  apps stop using the same local Game core.
- Rename Game-owned event adapter packages only if package ownership becomes a
  real compile-time coupling problem.
