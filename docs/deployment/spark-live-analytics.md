# Spark Live Analytics — Deployment Runbook

## What it does

`GameEventStreamingAnalyticsJob` is a Spark Structured Streaming job that:

1. Reads from Kafka topic `searchess.game.events.v1`
2. Filters `GameFinished` events
3. Writes deduplicated rows to `public.live_game_results` in PostgreSQL (via JDBC, `ON CONFLICT DO NOTHING`)

The analytics-service exposes these rows at `GET /api/analytics/live/game-results?limit=50`.
The web-ui renders them in the "Live Completed Games" section of the Analytics page.

---

## Deployment

### Kubernetes (uni-server-registry overlay)

The job runs as a single-replica Deployment (not a Spark cluster):

```
deployment/k8s/base/spark-analytics/
  pvc.yaml         — 1 Gi PVC for Structured Streaming checkpoint state
  configmap.yaml   — Kafka / Spark / Postgres env vars
  deployment.yaml  — Deployment (1 replica, 2 Gi limit)
```

Image: `ghcr.io/arutepsu/searchess-spark-analytics:sha-placeholder`
(replace `sha-placeholder` with the actual image tag after the first CI build)

The pod reads `POSTGRES_PASSWORD` from the `searchess-secrets` Secret (key: `postgres-password`).

#### Step 1 — Build the image

```sh
docker build -t searchess/spark-analytics:local -f deployment/docker/Dockerfile.spark-analytics .
```

#### Step 2 — Tag and push to GHCR

```sh
# Replace <sha> with the 7-char git commit hash (matches CI convention sha-<sha>)
SHA=$(git rev-parse --short HEAD)
docker tag searchess/spark-analytics:local ghcr.io/arutepsu/searchess-spark-analytics:sha-${SHA}
docker push ghcr.io/arutepsu/searchess-spark-analytics:sha-${SHA}
```

#### Step 3 — Update the overlay image tag

In `deployment/k8s/overlays/uni-server-registry/kustomization.yaml`, replace:
```yaml
- name: searchess/spark-analytics
  newName: ghcr.io/arutepsu/searchess-spark-analytics
  newTag: sha-placeholder
```
with the real tag (`sha-${SHA}`), then commit and push to trigger Argo CD sync.

#### Step 4 — Apply / validate kustomize

```sh
# Dry-run render (no cluster access needed)
kubectl kustomize deployment/k8s/overlays/uni-server-registry

# Apply via Argo CD (selfHeal=true — auto-syncs on commit) or manually:
kubectl apply -k deployment/k8s/overlays/uni-server-registry
```

#### Step 5 — Check pods and PVC

```sh
# Pod status
kubectl get pods -n searchess -l app=spark-analytics

# PVC status (must be Bound before the pod starts)
kubectl get pvc -n searchess spark-analytics-checkpoints

# Describe pod if it's stuck
kubectl describe pod -n searchess -l app=spark-analytics
```

#### Step 6 — Follow logs

```sh
kubectl logs -n searchess deployment/spark-analytics -f
```

Key log lines:
- `=== Live Game Event Streaming Analytics ===` — startup
- `[LiveGameResultsSink] Table public.live_game_results ready.` — DDL check passed
- `[LiveGameResultsSink] Wrote N rows …` — each batch write

### Docker Compose (local dev)

The service is gated behind the `spark-analytics` Compose profile so it does not start by default:

```sh
# Start the full stack plus the streaming job
docker compose -f deployment/compose/docker-compose.yml \
               --profile spark-analytics up -d

# Or add it to an existing running stack
docker compose -f deployment/compose/docker-compose.yml \
               --profile spark-analytics up -d spark-analytics
```

Checkpoint volume: `searchess_spark_checkpoints` (named Docker volume, persists across restarts).

---

## Configuration reference

| Variable | Default | Notes |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Internal Kafka address |
| `KAFKA_GAME_EVENTS_TOPIC` | `searchess.game.events.v1` | Source topic |
| `SPARK_CHECKPOINT_DIR` | `/spark-checkpoints/live-game-events` | Must be on a persistent volume |
| `SPARK_STREAMING_STARTING_OFFSETS` | `latest` | Use `earliest` to reprocess all historical events |
| `SPARK_STREAMING_TRIGGER_SECONDS` | `10` | Micro-batch interval |
| `POSTGRES_WRITE_ENABLED` | `true` | Must be `true`; job exits otherwise |
| `POSTGRES_URL` | `jdbc:postgresql://postgres:5432/searchess` | |
| `POSTGRES_USER` | `searchess` | |
| `POSTGRES_PASSWORD` | — | From `searchess-secrets` in K8s; from `.env` in Compose |
| `POSTGRES_SCHEMA` | `public` | Read by PostgresConfig; sink always writes to `public.live_game_results` |

---

## Table lifecycle

`LiveGameResultsSink.ensureTableExists` runs on startup and issues `CREATE TABLE IF NOT EXISTS public.live_game_results`. No migration tool is needed — the first run creates the table.

`analytics-service` uses `runAllowingMissingTable` (catches SQLSTATE 42P01) to return `{"rows":[]}` if the job has never run.

---

## Scaling constraints

The job runs `local[*]` (driver and executor in one JVM). Scaling replicas beyond 1 is not safe — Structured Streaming checkpoint state is exclusive to a single driver. To scale horizontally, move checkpoint storage to shared object storage (MinIO / S3) first.

---

## Verifying end-to-end

### Kafka UI — inspect events in flight

```sh
# K8s port-forward (kafka-ui runs in the searchess namespace)
kubectl port-forward -n searchess svc/kafka-ui 8888:8080

# Open http://localhost:8888 → Topics → searchess.game.events.v1
# Messages should appear when a game finishes.
```

### Analytics endpoint

```sh
# Replace <port> with the port-forward or LoadBalancer address for analytics-service
curl -s "http://localhost:<port>/api/analytics/live/game-results?limit=10" | python3 -m json.tool
# Or through Envoy (local Compose stack):
curl -s -H "Authorization: Bearer <token>" \
  "http://localhost:11000/api/analytics/live/game-results?limit=10" | python3 -m json.tool
```

Expected response shape:
```json
{ "rows": [ { "eventId": "...", "result": "WhiteWins", "occurredAt": "...", ... } ] }
```

### Web-UI

Navigate to the Analytics page (`/analytics`). The "Live Completed Games" section should show
rows. If no rows appear and the endpoint returns `{"rows":[]}`, the job either has not run yet
or `SPARK_STREAMING_STARTING_OFFSETS=latest` and no new games have finished since the job started.

---

## `startingOffsets=latest` behaviour

**Events that occurred before the streaming job first started are not backfilled.**
With the default `SPARK_STREAMING_STARTING_OFFSETS=latest`, Spark only consumes messages
published after the job reads its initial offset from Kafka. To populate the table with
historical `GameFinished` events, follow the reprocessing steps below.

---

## Reprocessing from earliest

To replay all historical `GameFinished` events and backfill the table:

1. Delete the checkpoint volume — this resets Spark's offset pointer:
   ```sh
   # K8s
   kubectl delete pvc -n searchess spark-analytics-checkpoints
   # Compose
   docker volume rm searchess_spark_checkpoints
   ```
2. Set `SPARK_STREAMING_STARTING_OFFSETS=earliest` in the ConfigMap / environment.
3. Restart the pod / container.

Duplicate events are safe — the `ON CONFLICT (event_id) DO NOTHING` constraint prevents double-writes.

**Note**: deleting the PVC also discards the progress checkpoint, so the job will re-read from
the configured starting offset. Without changing `startingOffsets` to `earliest`, resetting the
PVC alone has no effect on which events are consumed.

---

## Logs (Compose)

```sh
docker compose -f deployment/compose/docker-compose.yml logs -f spark-analytics
```
