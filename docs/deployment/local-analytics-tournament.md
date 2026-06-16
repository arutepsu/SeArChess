# Local deployment: analytics-service and tournament-service

Deploy Phase 1 (Docker Compose) + Deploy Phase 4 (deployable Spark analytics). Covers
running `analytics-service` and `tournament-service` locally through Docker Compose,
alongside the existing Postgres-backed stack, including a working `/analyze` endpoint.
Does not cover Kubernetes (see `docs/deployment/uni-analytics-tournament.md`) or Kafka.

## What this phase adds

- `Dockerfile.analytics` — packages `analyticsService` via `sbt stage`, runs on port 8084.
- `Dockerfile.tournament` — packages `tournamentService` **and** a staged `spark-analytics`
  distribution (also via `sbt stage`, same build invocation) on port 8085. No sbt and no
  monorepo source tree are present in the runtime image — see "Spark analytics now runs
  in-container" below.
- `analytics-service` and `tournament-service` entries in `docker-compose.yml` (root dev
  stack) and `deployment/compose/docker-compose.yml` (fuller local stack).
- Routes in `config/envoy/envoy.yaml` for `/api/analytics/*` and `/api/tournaments/*`,
  placed before the generic `/api/*` → game-service rule (shared by both compose files).
- New env vars documented in `.env.example`.

Kafka is **not** deployed and the Kafka outbox publisher is **not** enabled — see below.

## Starting the stack

From the repo root:

```
docker compose up -d postgres analytics-service tournament-service
```

This builds both new images (first run only) and starts them once Postgres reports
healthy. To bring up the whole dev stack (game-service, history-service, etc. too):

```
docker compose up -d
```

## Required env vars

Both services connect to the same `postgres` container used by game-service/history-service,
each in its own schema (default `public` for both — change via `ANALYTICS_POSTGRES_SCHEMA` /
`TOURNAMENT_POSTGRES_SCHEMA` in `.env` if you want to separate them from each other or from
`game`/`history`).

**analytics-service**

| Var | Default in compose | Notes |
|---|---|---|
| `ANALYTICS_HTTP_HOST` | `0.0.0.0` | |
| `ANALYTICS_HTTP_PORT` | `8084` | |
| `ANALYTICS_POSTGRES_URL` | `jdbc:postgresql://postgres:5432/searchess` | |
| `ANALYTICS_POSTGRES_USER` | `searchess` | |
| `ANALYTICS_POSTGRES_PASSWORD` | `searchess` | demo-only credential |
| `ANALYTICS_POSTGRES_SCHEMA` | `public` | |

**tournament-service**

| Var | Default in compose | Notes |
|---|---|---|
| `TOURNAMENT_HTTP_HOST` | `0.0.0.0` | |
| `TOURNAMENT_HTTP_PORT` | `8085` | |
| `TOURNAMENT_JOB_STORE` | `postgres` | jobs persist across restarts |
| `TOURNAMENT_POSTGRES_URL` | `jdbc:postgresql://postgres:5432/searchess` | |
| `TOURNAMENT_POSTGRES_USER` | `searchess` | |
| `TOURNAMENT_POSTGRES_PASSWORD` | `searchess` | demo-only credential |
| `TOURNAMENT_POSTGRES_SCHEMA` | `public` | |
| `TOURNAMENT_OUTBOX_PUBLISHER_ENABLED` | `true` | poller runs |
| `TOURNAMENT_OUTBOX_PUBLISHER_TYPE` | `logging` | no broker required |
| `TOURNAMENT_OUTPUT_BASE_PATH` | `/data/tournament-jobs` | backed by the `tournament-jobs` named volume |
| `TOURNAMENT_ANALYTICS_COMMAND` | `/app/spark-analytics/bin/run-analytics.sh` | packaged Spark executable baked into the image (Phase 4) |
| `TOURNAMENT_ANALYTICS_WORKING_DIR` | `/app` | working directory for the Spark subprocess |
| `TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH` | `/data/tournament-jobs/analytics-output` | Spark output, same volume as job JSONL |
| `POSTGRES_WRITE_ENABLED` / `POSTGRES_URL` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_SCHEMA` / `POSTGRES_WRITE_MODE` / `POSTGRES_STRICT_WRITE` | see compose file | read by the **spark-analytics subprocess** (`PostgresConfig.fromEnv`), inherited from this container's environment — not read by tournament-service itself. `POSTGRES_SCHEMA` must match `ANALYTICS_POSTGRES_SCHEMA` so analytics-service can read what Spark writes. |

## Health checks

```
curl http://localhost:8084/health
curl http://localhost:8085/health
```

Both compose files also wire `healthcheck:` blocks (`curl -fsS .../health`) so
`docker compose ps` shows `healthy` once each service is actually accepting requests,
not just once the container has started.

## Creating a small tournament

```
curl http://localhost:8085/api/tournaments/bots

curl -X POST http://localhost:8085/api/tournaments \
  -H "Content-Type: application/json" \
  -d '{"selectedBotIds": ["random-bot", "capture-first"], "mode": "single-round-robin", "repetitions": 1, "maxPly": 40}'

curl http://localhost:8085/api/tournaments/<jobId>
```

## Inspecting Postgres state

```
docker compose exec postgres psql -U searchess -d searchess \
  -c "select job_id, status, completed_games, planned_games from tournament_jobs order by created_at desc limit 5;"

docker compose exec postgres psql -U searchess -d searchess \
  -c "select event_type, status, attempt_count, published_at from tournament_outbox_events order by created_at desc limit 20;"
```

With the logging publisher, outbox rows move from `pending` to `published` on the next
poll cycle (default every 5s); confirm by also checking the log line:

```
docker compose logs tournament-service | grep outbox_event_published
```

## Spark analytics now runs in-container (Phase 4)

`POST /api/tournaments/{jobId}/analyze` works inside the container as of Phase 4.
`Dockerfile.tournament` stages **two** sbt projects in the same build step
(`sbt "tournamentService / stage" "sparkAnalytics / stage"`) and copies both staged
distributions into the runtime image — no sbt binary, no monorepo source tree at
runtime, only the two `target/universal/stage/` outputs.

`TOURNAMENT_ANALYTICS_COMMAND` points at `/app/spark-analytics/bin/run-analytics.sh`,
a thin wrapper (`apps/spark-analytics/docker/run-analytics.sh`) that sets `JAVA_OPTS`
to the `--add-opens` flags Spark 3.5.x needs on Java 17+ (must stay in sync with
`sparkJvmOpens` in `build.sbt`) and `exec`s the staged `bin/spark-analytics` binary.
`tournament-service`'s `PackagedSparkAnalyticsProcessRunner` invokes it with
`<inputPath> <outputPath>` as plain positional arguments — no sbt batch-command string
wrapping, unlike the legacy `SparkTournamentAnalyticsProcessRunner` (still the default
when `TOURNAMENT_ANALYTICS_COMMAND` is unset — e.g. plain `sbt tournamentService/run`
local dev keeps using `TOURNAMENT_ANALYTICS_SBT_COMMAND` as before).

This makes the `tournament-service` image noticeably larger (~400MB content size vs
~190MB without Spark) — accepted for this phase. The image-size and architecture
tradeoff is documented in "Known limitations" below.

Once a job succeeds, just call:

```
curl -X POST http://localhost:8085/api/tournaments/<jobId>/analyze
```

and poll `GET /api/tournaments/<jobId>` for `analysisStatus: "succeeded"`. Once Spark
writes the analytics tables, `GET http://localhost:8084/api/analytics/runs` will list
the new run.

## analytics-service on a fresh database (Phase 4)

`GET /api/analytics/runs` now returns `{"runs": []}` instead of a `503` when the
`analytics_leaderboard` table doesn't exist yet (PostgreSQL SQLSTATE `42P01`,
undefined_table) — i.e. before Spark has ever written to this Postgres instance. All
other `/api/analytics/*` endpoints (`/latest/*`, `/runs/{runId}/*`) are unchanged and
still return `503 ANALYTICS_UNAVAILABLE` on any query failure, including a missing
table — only the run-listing endpoint, which a client typically checks first to decide
whether there's anything to show, was changed. There is no automated test for this
specific behavior: `SlickAnalyticsRepository` has no existing Postgres-backed test
harness (it's excluded from coverage in `build.sbt`), and adding one (e.g. via
testcontainers) was judged too invasive for this phase. Verified manually instead —
see Phase 4 validation notes.

## Why Kafka is disabled in this phase

`KafkaOutboxPublisher` exists and is fully tested (Phase 11E), but no Kafka broker is
deployed anywhere in the local Compose stack, and none is added in this phase. Setting
`TOURNAMENT_OUTBOX_PUBLISHER_TYPE=kafka` without `TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS`
is rejected at config-load time, so the service simply won't start misconfigured.
Adding Kafka is deferred until there is an actual consumer for
`searchess.tournament.lifecycle.v1`.

## Why Spark is not a permanent service

`spark-analytics` is a batch job, not an HTTP service — it has no listening port, no
health endpoint, and is meant to run once per analysis request and exit. Running it as
an always-on container would hold a JVM + Spark runtime idle between tournaments for no
benefit. It stays a manually-triggered (or tournament-service-triggered, once that path
is fixed) job in every phase, local and production alike.

## Known limitations (Phase 4)

- **Image size.** Bundling spark-analytics into `tournament-service` roughly doubles
  the image's content size. Acceptable for this assignment; not the long-term shape.
- **Spark runs inside the tournament-service pod/container**, sharing its CPU/memory
  with the HTTP service itself. A heavy analysis run can starve the service's own
  request handling while it's in progress. There's no concurrency limit beyond
  `TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS` (still in-process, not resource-isolated).
- **`--add-opens` flags are duplicated** between `build.sbt`'s `sparkJvmOpens` (used for
  `sbt sparkAnalytics/run`) and `apps/spark-analytics/docker/run-analytics.sh` (used by
  the packaged path). They must be kept in sync manually if Spark's JDK requirements
  change.
- **Long-term direction:** extract a separate `analytics-executor` service, or trigger
  a Kubernetes `Job` per analysis request, so `tournament-service` itself never needs
  Spark's dependencies or runtime footprint. Not implemented in this phase — see
  Option 3/4 in the Phase 4 discovery notes.

The legacy nested-sbt path (`SparkTournamentAnalyticsProcessRunner`,
`TOURNAMENT_ANALYTICS_SBT_COMMAND`) still exists and remains the default when
`TOURNAMENT_ANALYTICS_COMMAND` is unset — local `sbt tournamentService/run` development
is unaffected by this phase.
