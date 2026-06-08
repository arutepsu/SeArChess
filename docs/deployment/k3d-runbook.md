# k3d Local Kubernetes Runbook

Local Kubernetes environment for Searchess using [k3d](https://k3d.io) + [Kustomize](https://kustomize.io).

**Prerequisites**

| Tool | Minimum version | Install |
|------|----------------|---------|
| Docker | 24+ | https://docs.docker.com/get-docker/ |
| k3d | 5.7+ | `brew install k3d` / `choco install k3d` |
| kubectl | 1.29+ | `brew install kubectl` / `choco install kubernetes-cli` |
| Kustomize | 5.3+ (or use `kubectl kustomize`) | bundled with kubectl |

---

## 1. Create the k3d cluster

```bash
# From the repo root:
k3d cluster create --config deployment/k3d/cluster.yaml
```

This creates a single-server k3d cluster named `searchess` with:
- Traefik disabled (Envoy is the edge proxy)
- Host port 10000 → Envoy (edge traffic)
- Host port 3001 → Grafana (observability UI; 3001 avoids conflict with Docker Compose Grafana on 3000)

Verify the cluster is ready:
```bash
kubectl cluster-info
kubectl get nodes
```

---

## 2. Build Docker images

Run these from the repo root. The three application services must be built before importing.

```bash
# Game service
docker build -t searchess/game-service:local .

# History service
docker build -t searchess/history-service:local -f Dockerfile.history .

# AI service
docker build -t searchess/ai-service:local -f Dockerfile.ai .
```

Third-party images (Envoy, Postgres, Mongo, Redis, Prometheus, Grafana) are pulled
automatically from Docker Hub when pods start; they do not need to be imported.

---

## 3. Import images into k3d

k3d runs a container registry inside Docker. Images built locally must be imported
before Kubernetes can use them (because `imagePullPolicy: Never` is set in the overlay).

```bash
k3d image import \
  searchess/game-service:local \
  searchess/history-service:local \
  searchess/ai-service:local \
  --cluster searchess
```

Verify the import succeeded:
```bash
k3d image list --cluster searchess | grep searchess/
```

---

## 4. Validate the Kustomize manifests (dry-run)

```bash
# Print the resolved manifests without applying
kubectl kustomize deployment/k8s/overlays/local-k3d
```

Check the output for:
- `image: searchess/game-service:local` (not `latest`)
- `imagePullPolicy: Never` for app services
- `type: LoadBalancer` for envoy and grafana Services
- 3 StatefulSets (postgres, mongo, redis)
- StatefulSet-owned PVCs for postgres, mongo, and redis
- `SEARCHESS_POSTGRES_SCHEMA: game` for game-service. This keeps active gameplay
  tables and the game-service Flyway history table out of `public`.
- `HISTORY_POSTGRES_SCHEMA: history` for history-service. This keeps Flyway's
  history tables and archive tables isolated from game-service ownership and
  avoids Flyway's non-empty-schema startup guard.
- `HISTORY_REDIS_STREAM: searchess.history.archives`, `HISTORY_DELIVERY_MODE:
  redis-stream`, and `HISTORY_INGESTION_MODE: redis-stream`.

---

## 5. Apply the overlay

```bash
kubectl apply -k deployment/k8s/overlays/local-k3d
```

Expected output (first apply):

```
namespace/searchess created
configmap/ai-service-env created
configmap/envoy-config-<hash> created
...
secret/searchess-secrets created
service/envoy created
...
statefulset.apps/postgres created
statefulset.apps/mongo created
statefulset.apps/redis created
deployment.apps/game-service created
...
```

Subsequent applies are idempotent — resources are patched, not re-created.

---

## 6. Check pod health

JVM services take ~60–90 s to pass readiness probes on first boot.

```bash
# Watch pods come up (Ctrl-C when all Running/1 ready)
kubectl get pods -n searchess -w

# Expected steady state:
# NAME                               READY   STATUS    RESTARTS
# ai-service-<hash>                  1/1     Running   0
# envoy-<hash>                       1/1     Running   0
# game-service-<hash>                1/1     Running   0
# grafana-<hash>                     1/1     Running   0
# history-service-<hash>             1/1     Running   0
# mongo-0                            1/1     Running   0
# postgres-0                         1/1     Running   0
# prometheus-<hash>                  1/1     Running   0
# redis-0                            1/1     Running   0
```

Check Services:
```bash
kubectl get svc -n searchess
```

Describe a specific pod (replace with actual pod name):
```bash
kubectl describe pod -n searchess <pod-name>
```

---

## 7. Access Envoy and Grafana

| Service | URL | Notes |
|---------|-----|-------|
| Envoy edge | http://localhost:10000/health | Game service health via proxy |
| Envoy API | http://localhost:10000/api/ | REST API |
| Grafana (k3d) | http://localhost:3001 | admin / admin (dev default) |

> **Port note:** k3d Grafana is on **3001**, not 3000. Host port 3000 is reserved for
> the Docker Compose Grafana. The in-cluster Grafana Service and container still use
> port 3000; the 3001 translation happens at the k3d load-balancer level.

Quick smoke tests:
```bash
# Edge health check
curl http://localhost:10000/health

# Grafana health (k3d — port 3001)
curl http://localhost:3001/api/health

# List Grafana data sources (expect Prometheus listed)
curl -s -u admin:admin http://localhost:3001/api/datasources | jq '.[].name'
```

---

## 8. View logs

```bash
# All pods in namespace (most recent 50 lines)
kubectl logs -n searchess -l app=game-service --tail=50

# Follow a specific pod's logs
kubectl logs -n searchess <pod-name> -f

# Previous container logs (after crash/restart)
kubectl logs -n searchess <pod-name> --previous
```

---

## 9. Rebuild and redeploy a single service

After making code changes to an application service:

```bash
# 1. Rebuild the image
docker build -t searchess/game-service:local .

# 2. Import the updated image
k3d image import searchess/game-service:local --cluster searchess

# 3. Restart the deployment to pull the new image
kubectl rollout restart deployment/game-service -n searchess

# 4. Watch rollout
kubectl rollout status deployment/game-service -n searchess
```

---

## 10. Update and re-apply manifests

After changing any YAML under `deployment/k8s/`:

```bash
# Dry-run to review changes
kubectl diff -k deployment/k8s/overlays/local-k3d

# Apply
kubectl apply -k deployment/k8s/overlays/local-k3d
```

---

## 11. Delete the cluster

```bash
k3d cluster delete searchess
```

This removes the k3d cluster, all pods, and the k3d-managed Docker volumes.
Named Docker volumes created outside k3d (host bind mounts, etc.) are not removed.

---

## Troubleshooting

### Pod stuck in `Pending`
```bash
kubectl describe pod -n searchess <pod-name>
```
Common causes:
- PVC not bound (storage class missing) — check `kubectl get pvc -n searchess`
- Image not found — verify `k3d image list --cluster searchess`

### Pod `CrashLoopBackOff`
```bash
kubectl logs -n searchess <pod-name> --previous
```
Common causes:
- JVM OOM: increase memory limit in the Deployment
- Wrong env var: check `kubectl describe pod -n searchess <pod-name>` → Environment section

### history-service fails to start — missing HISTORY_POSTGRES_URL

history-service now uses Postgres. If `HISTORY_POSTGRES_URL` is absent or empty, the service
logs `configuration_error` and exits with code 1. Ensure the ConfigMap and Secret are applied:

```bash
kubectl describe configmap history-service-env -n searchess
kubectl describe secret searchess-secrets -n searchess
```

The `HISTORY_POSTGRES_URL` in the base ConfigMap points to `jdbc:postgresql://postgres:5432/searchess`.
The `HISTORY_POSTGRES_SCHEMA` value should be `history`; Flyway creates that schema and uses
`history.flyway_schema_history`, while Slick reads and writes `history.history_archives`.
If the postgres pod is not yet healthy when history-service starts, the Flyway migration will fail
and the service will crash. Wait for postgres to be `1/1 Running` before troubleshooting:

```bash
kubectl get pods -n searchess -l app=postgres
```

### game-service fails to start - public schema has existing tables

game-service uses schema isolation rather than `baselineOnMigrate`. The
`SEARCHESS_POSTGRES_SCHEMA` value should be `game`; Flyway creates
`game.flyway_schema_history`, `game.sessions`, and `game.game_states`, while
Slick reads and writes the same schema.

Check the rendered and live config:

```bash
kubectl kustomize deployment/k8s/overlays/local-k3d | grep SEARCHESS_POSTGRES_SCHEMA
kubectl describe configmap game-service-env -n searchess | grep SEARCHESS_POSTGRES_SCHEMA
```

Verify owned tables:

```bash
kubectl exec -n searchess postgres-0 -- psql -U searchess -d searchess -c "\dt game.*"
kubectl exec -n searchess postgres-0 -- psql -U searchess -d searchess -c "\dt history.*"
kubectl exec -n searchess postgres-0 -- psql -U searchess -d searchess -c "\d history.history_archives"
kubectl exec -n searchess postgres-0 -- psql -U searchess -d searchess -c "select column_name, data_type, is_nullable from information_schema.columns where table_schema = 'history' and table_name = 'history_archives' and column_name in ('owner_user_id', 'owner_nickname_snapshot', 'source') order by column_name;"
```

`history.history_archives` is the PostgreSQL archive table. `searchess.history.archives`
is the Redis stream name, not a Postgres relation.

### `mongo-0` CrashLoopBackOff — probe timeout

**Symptom:** `kubectl describe pod -n searchess mongo-0` shows probe failures:
```
Readiness probe failed: command timed out after 1s
Liveness probe failed: command timed out after 1s
```
Mongo logs (`kubectl logs -n searchess mongo-0`) show `"Waiting for connections"` and `"mongod startup complete"` — the server itself is healthy.

**Root cause:** The original exec probes ran `mongosh --eval db.adminCommand('ping')`, which spawns a
Node.js process inside the container. Under k3d/Docker Desktop, this frequently exceeds the 1 s timeout
even though MongoDB is accepting connections, causing Kubernetes to kill and restart the pod.

**Fix:** The Mongo StatefulSet (`deployment/k8s/base/mongo/statefulset.yaml`) uses `tcpSocket` probes on
port 27017 instead of exec probes. A successful TCP connection proves the listener is up without executing
a heavyweight process:

```yaml
readinessProbe:
  tcpSocket:
    port: 27017
  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 6

livenessProbe:
  tcpSocket:
    port: 27017
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 6
```

After applying the fix, `mongo-0` should reach `1/1 Running` within ~40 s of the pod starting.

### history-service not receiving game events

**Symptom:** Game sessions complete but no archive records are created in history-service.

**Check 1 — stream and consumer group exist:**
```bash
kubectl exec -n searchess -it redis-0 -- redis-cli XINFO STREAM searchess.history.archives
kubectl exec -n searchess -it redis-0 -- redis-cli XINFO GROUPS searchess.history.archives
```
Expect a group named `history-service`. If absent, history-service either never started its consumer
or failed to connect.

**Check 2 — pending entries (messages not yet ACKed):**
```bash
kubectl exec -n searchess -it redis-0 -- redis-cli XPENDING searchess.history.archives history-service - + 10
```
Empty output is healthy. Non-zero entries indicate the consumer received messages but failed to process them (archive fetch or persistence failure).

**Check 3 — history-service logs:**
```bash
kubectl logs -n searchess -l app=history-service --tail=50
```
Look for `redis_consumer_started` (startup), `redis_consumer_ingested` (successful ingest), or
`redis_consumer_left_in_pel` / `redis_consumer_loop_error` (failures).

**Check 4 — Redis mode config:**
```bash
kubectl describe configmap game-service-env -n searchess | grep HISTORY_DELIVERY_MODE
kubectl describe configmap history-service-env -n searchess | grep HISTORY_INGESTION_MODE
kubectl describe configmap history-service-env -n searchess | grep HISTORY_REDIS_STREAM
```
Both modes should be `redis-stream` and the stream should be
`searchess.history.archives`. If the mode is `http`, direct HTTP delivery is in
use as the fallback.

---

### `ImagePullBackOff` for app services
The image was not imported into k3d. Re-run step 3.

### Envoy returns 503
game-service is not yet healthy. Wait for readiness probe to pass:
```bash
kubectl get pods -n searchess -l app=game-service
```

### Grafana dashboards missing
ConfigMaps are provisioned at startup. If you applied after Grafana started:
```bash
kubectl rollout restart deployment/grafana -n searchess
```

---

## Known limitations

| Limitation | Impact | Resolution |
|------------|--------|-----------|
| history-service replicas: 1 | Single instance pending load validation | Postgres backend enables safe scaling; increase after testing |
| Plain Secrets in `secret-dev.yaml` | Dev values in git | Add Sealed Secrets (deferred) |
| No TLS at Envoy | Plain HTTP on port 10000 | Add TLS listener before production |
| Redis has no auth | Any pod in namespace can connect | Add `requirepass` before production |
| Prometheus + Grafana use emptyDir | Metrics lost on pod restart | Add PVCs for long-lived monitoring |
