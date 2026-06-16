# University server (K8s/k3d): analytics-service and tournament-service

Deploy Phase 2 (K8s manifests) + Deploy Phase 4 (deployable Spark analytics). Extends
the existing Kustomize-based K8s deployment (used by `local-k3d`, `local-k3d-fast`,
`uni-server-k3d`, `uni-server-registry`) to include `analytics-service` and
`tournament-service`, following the same conventions as `history-service`/`user-service`.
Builds on [docs/deployment/local-analytics-tournament.md](local-analytics-tournament.md)
— read that first for the full env var reference and how the packaged Spark analytics
command works; this doc covers only the K8s-specific wiring.

## K8s resources added

`deployment/k8s/base/analytics-service/{deployment,service,configmap}.yaml` and
`deployment/k8s/base/tournament-service/{deployment,service,configmap}.yaml`,
wired into `deployment/k8s/base/kustomization.yaml`. Both follow the
`history-service` pattern exactly: `ClusterIP` Service, `envFrom` ConfigMap +
one `secretKeyRef` env var for the Postgres password, `GET /health`
readiness/liveness probes (`initialDelaySeconds: 30/60`, matching every other
JVM service here), `replicas: 1`.

`tournament-service` additionally mounts an `emptyDir` volume at
`/data/tournament-jobs` (see "Tournament output volume" below).

**Phase 4 update:** `tournament-service`'s memory limit was raised from `1Gi` to `2Gi`
(`deployment/k8s/base/tournament-service/deployment.yaml`) because `POST /analyze` now
spawns a local-mode Spark JVM as a child process inside the same pod — see "Spark
analytics now runs in-container" below.

## Envoy routes added

`deployment/k8s/base/envoy/envoy.yaml` gained two routes and two clusters,
placed **before** the generic `prefix: /api/` → `game_service_http` rule (same
position as the existing `/api/users` and `/api/lichess/*` carve-outs):

```
/api/analytics/*   → analytics_service_http (analytics-service:8084)
/api/tournaments/* → tournament_service_http (tournament-service:8085)
```

No `prefix_rewrite` — both services already serve their routes at
`/api/analytics/...` and `/api/tournaments/...`, so the path passes through
unchanged. No JWT rule changes were needed: both new prefixes already fall
under the existing `prefix: "/api/"` JWT-required rule, the same as
`/api/users/*` today.

## Required env / secret keys

ConfigMap values (non-secret): `ANALYTICS_HTTP_HOST=0.0.0.0`,
`ANALYTICS_HTTP_PORT=8084`, `ANALYTICS_POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess`,
`ANALYTICS_POSTGRES_USER=searchess`, `ANALYTICS_POSTGRES_SCHEMA=public`;
`TOURNAMENT_HTTP_HOST=0.0.0.0`, `TOURNAMENT_HTTP_PORT=8085`,
`TOURNAMENT_JOB_STORE=postgres`, `TOURNAMENT_POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess`,
`TOURNAMENT_POSTGRES_USER=searchess`, `TOURNAMENT_POSTGRES_SCHEMA=public`,
`TOURNAMENT_OUTPUT_BASE_PATH=/data/tournament-jobs`,
`TOURNAMENT_OUTBOX_PUBLISHER_ENABLED=true`, `TOURNAMENT_OUTBOX_PUBLISHER_TYPE=logging`.

**Phase 5 additions** (`deployment/k8s/base/tournament-service/configmap.yaml`):
`SEARCHESS_AI_BASE_URL=http://ai-service:8765` and
`STOCKFISH_PATH=/usr/games/stockfish` — see "Bot runtime vars" above.

**Phase 4 additions** (`deployment/k8s/base/tournament-service/configmap.yaml`):
`TOURNAMENT_ANALYTICS_COMMAND=/app/spark-analytics/bin/run-analytics.sh`,
`TOURNAMENT_ANALYTICS_WORKING_DIR=/app`,
`TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH=/data/tournament-jobs/analytics-output`,
and the Spark-subprocess Postgres vars: `POSTGRES_WRITE_ENABLED=true`,
`POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess`, `POSTGRES_USER=searchess`,
`POSTGRES_SCHEMA=public`, `POSTGRES_WRITE_MODE=append`, `POSTGRES_STRICT_WRITE=true`.
These last `POSTGRES_*` vars (no `TOURNAMENT_`/`ANALYTICS_` prefix) are read by the
**Spark subprocess** (`PostgresConfig.fromEnv`, inherited from the pod's environment),
not by tournament-service itself — `POSTGRES_SCHEMA` must match
`ANALYTICS_POSTGRES_SCHEMA` so analytics-service can read what Spark writes.

Secret values (from the shared `searchess-secrets` Secret, same as every other
service): `ANALYTICS_POSTGRES_PASSWORD` / `TOURNAMENT_POSTGRES_PASSWORD` /
`POSTGRES_PASSWORD` (Phase 4, for the Spark subprocess) all read the existing
`postgres-password` key — no new Secret keys were added, since both services share
the same Postgres instance/credentials as `history-service`/`user-service`.

**Bot runtime vars** (set in ConfigMap):
- `STOCKFISH_PATH=/usr/games/stockfish` — Stockfish is installed inside the
  `tournament-service` image via `apt-get install stockfish` in `Dockerfile.tournament`.
  The binary lands at `/usr/games/stockfish` on the Linux runtime image; no host binary
  or Windows path is ever used inside the container.
- `SEARCHESS_AI_BASE_URL=http://ai-service:8765` — resolves to the `ai-service`
  ClusterIP Service over in-cluster DNS, enabling the `searchess-ai-v1` bot.

Both are now set unconditionally in the ConfigMap. For local host dev
(`sbt tournamentService/run` on Windows), override `STOCKFISH_PATH` in `.env` to
your Windows Stockfish binary path; set `SEARCHESS_AI_BASE_URL` only if an
ai-service instance is reachable from the host.

## Kafka — still disabled

No Kafka resources were added anywhere in `deployment/k8s/`. The ConfigMap
hardcodes `TOURNAMENT_OUTBOX_PUBLISHER_TYPE=logging` with a comment warning
against setting it to `kafka` without first adding a broker and
`TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS` (config validation rejects that
combination at service startup, same as Phase 1).

## Tournament output volume: emptyDir limitation

`tournament-service`'s `/data/tournament-jobs` mount is an `emptyDir`, not a
PersistentVolumeClaim. This means:

- Tournament JSONL game-event output **does not survive** pod restart,
  rescheduling, or node failure.
- `tournament_jobs` rows and `tournament_outbox_events` rows in Postgres are
  unaffected — they persist normally, since job state lives in the database,
  not the filesystem. Only the on-disk JSONL artifact is lost.
- This is an explicit, intentional simplification for this phase, not an
  oversight. Promote to a PVC once tournament output needs to survive a pod
  restart (e.g. to support replaying analysis after a restart, or scaling
  beyond `replicas: 1`).

## Spark analytics now runs in-container (Phase 4)

`POST /api/tournaments/{jobId}/analyze` now works in this K8s deployment.
`Dockerfile.tournament` stages both `tournamentService` and `sparkAnalytics` in the
same sbt invocation and copies both staged distributions into the runtime image — no
sbt, no monorepo source tree at runtime. `TOURNAMENT_ANALYTICS_COMMAND` points the
runner at the staged Spark executable's wrapper script instead of shelling out to sbt.
See [local-analytics-tournament.md](local-analytics-tournament.md#spark-analytics-now-runs-in-container-phase-4)
for the full mechanism (the `run-analytics.sh` wrapper, `--add-opens` handling,
`PackagedSparkAnalyticsProcessRunner`).

Remaining limitations in this K8s deployment specifically:
- The image is noticeably larger than a Spark-free tournament-service image would be.
- Spark's local-mode driver runs as a child process inside the tournament-service pod,
  sharing its CPU/memory — hence the `2Gi` memory limit bump noted above.
- Long-term, this should become a separate `analytics-executor` service or a
  Kubernetes `Job` per analysis request, keeping `tournament-service` itself
  Spark-free. Not implemented in this phase.

## analytics-service on a fresh database (Phase 4)

`GET /api/analytics/runs` now returns `{"runs": []}` instead of `503` when no Spark
run has ever written to this K8s Postgres instance (table `analytics_leaderboard`
doesn't exist yet, SQLSTATE `42P01`). Other `/api/analytics/*` endpoints are unchanged.
See [local-analytics-tournament.md](local-analytics-tournament.md#analytics-service-on-a-fresh-database-phase-4)
for detail and why this wasn't covered by an automated test.

## Image build / overlay wiring

- `.github/workflows/build-images.yml` gained `build-analytics-service` and
  `build-tournament-service` jobs (mirroring `build-history-service`),
  path-triggered by `apps/analytics-service/**` / `Dockerfile.analytics` and
  `apps/tournament-service/**` / `Dockerfile.tournament` respectively (also
  triggered by changes under their own `deployment/k8s/base/<service>/`
  directories, same reasoning as the existing `user-service` trigger).
- `deployment/k8s/overlays/local-k3d/kustomization.yaml`,
  `local-k3d-fast/kustomization.yaml`, and `uni-server-k3d/kustomization.yaml`
  all gained `searchess/analytics-service:local` /
  `searchess/tournament-service:local` image overrides plus
  `imagePullPolicy: Never` patches, matching every other locally-built service
  in those overlays.
- `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` gained
  `searchess/analytics-service` → `ghcr.io/arutepsu/searchess-analytics-service`
  and `searchess/tournament-service` → `ghcr.io/arutepsu/searchess-tournament-service`,
  both starting at `newTag: sha-placeholder` since no image has been pushed
  for either service yet. `build-images.yml`'s sha-placeholder safety net
  (previously specific to `user-service`'s bootstrap) was generalized to check
  each service's own image entry, so it now also force-rebuilds
  analytics-service/tournament-service on the next push regardless of which
  files changed, until their first real image lands.
- `deployment/server/import-images.sh` gained
  `searchess/analytics-service:local` / `searchess/tournament-service:local`
  in its `k3d image import` list and verification grep.

## Smoke test

```
kubectl get pods -n searchess

kubectl logs -n searchess deploy/analytics-service
kubectl logs -n searchess deploy/tournament-service
```

Through Envoy (port depends on overlay — `10000` for `uni-server-k3d`/`uni-server-registry`,
mapped per `deployment/k3d/server-cluster.yaml`; substitute your cluster's
mapped host port):

```
curl http://<envoy-host>:<port>/api/analytics/runs
curl http://<envoy-host>:<port>/api/tournaments/bots
```

Both require a valid bearer token in this deployment (the K8s Envoy enforces
JWT on all `/api/*` routes, unlike the local Compose Envoy which is JWT-free
for `/api/analytics`/`/api/tournaments` by default) — obtain one via the
existing Keycloak login flow documented in
`docs/deployment/uni-server-k3d-runbook.md` / `docs/deployment/k3d-runbook.md`.

Create a small tournament, then run analysis on it (Phase 4 — works in-cluster now):

```
curl -X POST http://<envoy-host>:<port>/api/tournaments \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"botIds": ["random-bot", "capture-first"], "mode": "double-round-robin", "repetitions": 1, "maxPly": 20}'

curl -X POST http://<envoy-host>:<port>/api/tournaments/<jobId>/analyze \
  -H "Authorization: Bearer <token>"

curl http://<envoy-host>:<port>/api/tournaments/<jobId> -H "Authorization: Bearer <token>"
# expect "analysisStatus":"succeeded" after a short delay
```

Query Postgres for job/outbox/analytics state (reusing the existing port-forward/psql
pattern from the k3d runbooks):

```
kubectl port-forward -n searchess svc/postgres 5432:5432

psql -h localhost -U searchess -d searchess \
  -c "select job_id, status, completed_games, planned_games from tournament_jobs order by created_at desc limit 5;"
psql -h localhost -U searchess -d searchess \
  -c "select event_type, status, attempt_count from tournament_outbox_events order by created_at desc limit 10;"
psql -h localhost -U searchess -d searchess \
  -c "select run_id, created_at from analytics_leaderboard order by created_at desc limit 5;"
```

Finally, confirm analytics-service surfaces the new run:

```
curl http://<envoy-host>:<port>/api/analytics/runs -H "Authorization: Bearer <token>"
```
