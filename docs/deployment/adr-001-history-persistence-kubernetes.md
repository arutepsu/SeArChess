# ADR-001: History Service Persistence Strategy for Kubernetes

**Status:** Superseded by the decision below  
**Original date:** 2026-05-19  
**Superseded date:** 2026-05-22  
**Deciders:** Engineering team

---

## Original decision (2026-05-19) — SQLite + ReadWriteOnce PVC

The initial k3d deployment used a `ReadWriteOnce` PersistentVolumeClaim with a SQLite file at
`/history-data/history.sqlite`.  This was chosen for its zero-migration footprint during the
first Kubernetes validation sprint.

Accepted constraints under that decision:
- `replicas: 1` enforced (single writer on a RWO volume).
- Node-bound volume; data loss risk if the node is lost.
- Rolling updates safe only because a single replica means the old pod releases the volume
  before the new pod attaches it.

This decision was always marked as temporary; migration to Postgres was the planned next step.

---

## Current decision (2026-05-22) — Postgres-only, Slick-backed history storage

history-service stores archive records in the same Postgres instance used by game-service,
but owns a dedicated schema (`postgres:5432/searchess`, schema `history`, table
`history_archives`). game-service owns a separate `game` schema for active gameplay
tables and its Flyway history.

SQLite is no longer a supported runtime option. `HISTORY_STORAGE_MODE` has been removed.

### Why Postgres

| Criterion | SQLite + PVC | Postgres |
|-----------|-------------|---------|
| Requires new infrastructure | No | No (already in every env) |
| Horizontal scaling | ❌ — single writer | ✅ — any replica can write |
| Node loss resilience | ❌ — volume loss = data loss | ✅ — Postgres is replicated separately |
| Migration path | N/A | Flyway V1 migration at startup |
| Operational overhead | Zero additional | Zero additional (shared Postgres) |

### Why Slick over plain JDBC

| Criterion | Plain JDBC | Slick |
|-----------|-----------|-------|
| Type safety | Manual binding | Compile-time checked |
| Upsert | Manual ON CONFLICT SQL | `insertOrUpdate` |
| Connection pool | Single connection | HikariCP via slick-hikaricp |
| Consistent with game-service | No | ✅ — game-service uses Slick for Postgres |

### Schema

history-service uses schema isolation rather than `baselineOnMigrate`. Flyway is configured with
`schemas("history")`, `defaultSchema("history")`, and schema creation enabled. Slick receives the
same configured schema and qualifies archive table access as `history.history_archives`.

Migration: `backend/services/history-service/modules/core/src/main/resources/db/migration/history/V1__create_history_archives.sql`

```sql
create table if not exists history_archives (
  game_id         uuid        primary key,
  session_id      uuid        not null,
  record_json     text        not null,
  created_at      timestamptz not null,
  closed_at       timestamptz not null,
  materialized_at timestamptz not null
);
```

`record_json` carries the full `ArchiveRecord` as JSON (source of truth for reads). Flyway remains
the schema source of truth; Slick does not generate DDL. The separate timestamp columns are
available for range queries if a query API is added later.

### Configuration

| Variable | Default | Notes |
|----------|---------|-------|
| `HISTORY_POSTGRES_URL` | — | **Required** |
| `HISTORY_POSTGRES_USER` | `searchess` | |
| `HISTORY_POSTGRES_PASSWORD` | — | Injected from `searchess-secrets` in k8s |
| `HISTORY_POSTGRES_SCHEMA` | `history` | Dedicated Flyway and Slick schema |

### Deployment changes

- `deployment.yaml`: removed `volumeMounts` and `volumes` (PVC); added `HISTORY_POSTGRES_PASSWORD`
  from `searchess-secrets`.
- `configmap.yaml`: replaced `HISTORY_DB_PATH` / `HISTORY_STORAGE_MODE` with Postgres connection vars.
- `kustomization.yaml`: removed `history-service/pvc.yaml` resource.
- `docker-compose.yml`: removed `HISTORY_STORAGE_MODE`, `HISTORY_DB_PATH`, and `history_data`
  volume; added `depends_on: postgres` and Postgres env vars.

### Schema migration strategy

`HistoryFlywaySchemaInitializer.migrate()` runs at service startup before the HTTP server
binds. Flyway applies any pending migrations and is idempotent on re-start. No manual
migration step is required. `baselineOnMigrate` is intentionally not used; the dedicated
`history` schema avoids collision with existing objects in `public`.

### Remaining constraint

`replicas: 1` is kept for initial validation. The Postgres backend enables safe horizontal
scaling; increase after load-testing confirms end-to-end idempotency of the ingestion flow.

### Delivery update

Archive delivery is asynchronous through Redis Streams in the validated local/k3d path.
game-service publishes `history.archive.requested` envelopes to
`searchess.history.archives`; history-service consumes them with the `history-service`
consumer group and acknowledges only after the existing ingestion path has written the archive
through Slick. Direct HTTP ingestion remains as a fallback mode.

---

## References

- `backend/services/history-service/modules/core/src/main/scala/chess/history/slick/` — Slick adapter
- `backend/services/history-service/modules/core/src/main/scala/chess/history/postgres/` — Flyway initializer
- `backend/services/history-service/modules/core/src/main/resources/db/migration/history/` — Flyway migration
- `deployment/k8s/base/history-service/` — updated manifests
- `docs/deployment/deployment-inventory.md` — service inventory
