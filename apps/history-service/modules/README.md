# History Service Modules

These modules are owned by the History Service runtime.

| Module | Role |
|---|---|
| `core` | Game event ingestion, Game archive HTTP client, archive materialization, and History-owned SQLite archive persistence. |

History depends on shared contracts such as `modules/game-contract`; it does not
depend on Game Service core internals.
