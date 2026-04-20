# Service-Oriented Repository Structure

Status: architecture cleanup  
Scope: directory/project ownership only

The repository now makes service ownership the dominant visual structure.
Shared contracts and reusable libraries remain under root `modules/`; service
implementation modules live beside the runnable service apps that own them.

## Classification

| Project | Classification | Location |
|---|---|---|
| `domain` | shared library | `modules/domain` |
| `notation` | shared library | `modules/notation` |
| `gameContract` | shared contract | `modules/game-contract` |
| `gameEventContract` | shared boundary contract | `modules/game-event-contract` |
| `adapterRestContract` | shared HTTP contract | `modules/adapter-rest-contract` |
| `gameCore` | Game-owned | `apps/game-service/modules/core` |
| `adapterPersistence` | Game-owned | `apps/game-service/modules/persistence` |
| `adapterRestHttp4s` | Game-owned | `apps/game-service/modules/rest-http4s` |
| `adapterWebsocket` | Game-owned | `apps/game-service/modules/websocket` |
| `adapterAi` | Game-owned | `apps/game-service/modules/ai` |
| `adapterEvent` | Game-owned internal eventing | `apps/game-service/modules/eventing` |
| `gameHistoryDelivery` | Game-owned delivery | `apps/game-service/modules/history-delivery` |
| `history` | History-owned | `apps/history-service/modules/core` |
| `adapterGui` | desktop client-owned | `apps/desktop-gui/modules/gui` |
| `adapterTui` | TUI client-owned | `apps/tui-cli/modules/tui` |
| `startupShared` | deferred shared local bootstrap | `apps/startup-shared` |
| `web-ui` | web client-owned | `apps/web-ui` |

## Resulting Shape

```text
apps/
  game-service/
    modules/
      ai/
      core/
      eventing/
      history-delivery/
      persistence/
      rest-http4s/
      websocket/
  history-service/
    modules/
      core/
  desktop-gui/
    modules/
      gui/
  tui-cli/
    modules/
      tui/
  startup-shared/
  web-ui/

modules/
  adapter-rest-contract/
  domain/
  game-contract/
  game-event-contract/
  notation/
```

## Why This Is Better

The previous structure made technical layers visually dominant:
`adapter-*`, `game-*`, and `history` all lived beside one another under
`modules/`. That made service ownership harder to see even after the runtime
architecture became service-oriented.

The new structure keeps shared contracts explicit while making ownership
obvious:

- Game Service owns its core, adapters, persistence, eventing, and History
  delivery modules.
- History Service owns its ingestion, materialization, client, and persistence
  module.
- UI apps own their local adapters.
- Root `modules/` contains only cross-service contracts and reusable libraries.

## Deferred

- SBT project IDs such as `adapterPersistence` and `adapterRestHttp4s` are kept
  stable to avoid command and import churn.
- Scala package names are not renamed.
- `startup-shared` remains as a small shared local bootstrap module because it
  is still used by local GUI/TUI startup paths.
- This is not a repo split and does not change runtime behavior.
