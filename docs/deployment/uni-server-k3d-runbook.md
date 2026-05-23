# University Server — k3d Deployment Runbook

Searchess backend deployed on the university VM (`141.37.74.145`) using k3d (Kubernetes in Docker).

---

## Architecture overview

| Layer | Tool | Host port |
|---|---|---|
| Edge proxy | Envoy | 10000 |
| Observability | Grafana | 33001 |
| Cluster | k3d (`searchess-server`) | — |
| Manifests | Kustomize overlay `uni-server-k3d` | — |

Direct TCP access to the VM from the developer machine is blocked by the university network
policy. All developer access goes through an SSH tunnel (see §7).

---

## Prerequisites on the server

- Docker installed and running (user is in the `docker` group — **no sudo needed**)
- `k3d` and `kubectl` installed in `~/bin` (not system-wide)
- Scripts prepend `~/bin` to `PATH` automatically

Verify:
```bash
export PATH="$HOME/bin:$PATH"
k3d version
kubectl version --client
```

---

## 1. Stop the old Docker Compose stack

The old Compose stack binds host port 10000 (Envoy). k3d also needs port 10000.
Stop Compose before creating the k3d cluster.

```bash
# From the repo root on the server:
docker compose down
```

Verify port 10000 is free before proceeding:
```bash
ss -tlnp | grep 10000   # must return empty
```

---

## 2. Create the k3d cluster

```bash
# From the repo root:
export PATH="$HOME/bin:$PATH"
k3d cluster create --config deployment/k3d/server-cluster.yaml
```

This creates a cluster named **`searchess-server`** with:
- Traefik disabled (Envoy is the edge proxy)
- Host port 10000 → Envoy LoadBalancer (port 10000)
- Host port 33001 → Grafana LoadBalancer (port 3000 in-cluster)

> **Port note:** Grafana maps to host port **33001** (not 3001) because port 3001 was
> already occupied on the university VM.

Verify the cluster is ready:
```bash
kubectl cluster-info --context k3d-searchess-server
kubectl get nodes
```

---

## 3. Build Docker images

Run from the repo root on a machine with Docker and sbt/JDK available.
Images can be built on the server itself or transferred as tar archives.

```bash
# Game service
docker build -t searchess/game-service:local .

# History service
docker build -t searchess/history-service:local -f Dockerfile.history .

# AI service (Scala random-legal engine)
docker build -t searchess/ai-service:local -f Dockerfile.ai .
```

Third-party images (Envoy, Postgres, Mongo, Redis, Prometheus, Grafana) are pulled
from Docker Hub automatically when pods start.

### Transferring images to the server (if built elsewhere)

```bash
# On build machine:
docker save searchess/game-service:local   | gzip > game-service.tar.gz
docker save searchess/history-service:local | gzip > history-service.tar.gz
docker save searchess/ai-service:local      | gzip > ai-service.tar.gz

scp game-service.tar.gz history-service.tar.gz ai-service.tar.gz chess@141.37.74.145:~/

# On server:
docker load < ~/game-service.tar.gz
docker load < ~/history-service.tar.gz
docker load < ~/ai-service.tar.gz
```

---

## 4. Import images into k3d

```bash
bash deployment/server/import-images.sh
```

This runs:
```bash
k3d image import \
  searchess/game-service:local \
  searchess/history-service:local \
  searchess/ai-service:local \
  --cluster searchess-server
```

Verify:
```bash
k3d image list --cluster searchess-server | grep searchess/
```

---

## 5. Validate the Kustomize manifests (dry-run)

```bash
kubectl kustomize deployment/k8s/overlays/uni-server-k3d
```

Check the output for:
- `image: searchess/game-service:local` (not `latest`)
- `image: mongo:4.4` (not `mongo:7.0` — see §Mongo note below)
- `imagePullPolicy: Never` for app services
- `type: LoadBalancer` for envoy and grafana Services
- `SEARCHESS_POSTGRES_SCHEMA: game` for game-service
- `HISTORY_POSTGRES_SCHEMA: history` for history-service
- `HISTORY_DELIVERY_MODE: redis-stream` and `HISTORY_INGESTION_MODE: redis-stream`

---

## 6. Apply the overlay

```bash
bash deployment/server/deploy-server-k3d.sh
```

Or directly:
```bash
export PATH="$HOME/bin:$PATH"
kubectl apply -k deployment/k8s/overlays/uni-server-k3d
```

Expected output (first apply):
```
namespace/searchess created
configmap/ai-service-env created
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

---

## 7. Check pod health

JVM services (game-service, history-service) take 60–90 s to pass readiness probes on first boot.

```bash
# Watch pods come up (Ctrl-C when all Running)
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

Run the verification script when all pods are Running:
```bash
bash deployment/server/verify-server-k3d.sh
```

---

## 8. Server-local health checks

These run directly on the server (no SSH tunnel needed):

```bash
# Edge health via Envoy
curl http://localhost:10000/health

# Grafana health
curl http://localhost:33001/api/health
```

---

## Mongo 4.4 — AVX requirement

MongoDB 7.0 requires AVX (Advanced Vector Extensions) CPU instructions. The university VM
does not expose AVX to guest VMs. Running `mongo:7.0` causes an immediate `Illegal instruction`
crash at startup.

The `uni-server-k3d` overlay sets `mongo` image tag to `4.4` via the `images` block in
`kustomization.yaml`. `mongo:4.4` is the last MongoDB release that runs without AVX.

The base manifests keep `mongo:7.0` unchanged. Only the `uni-server-k3d` overlay downgrades.

---

## Developer workflow — connecting from your local machine

### Terminal 1 — SSH tunnel (keep open)

```bash
ssh -L 10000:localhost:10000 -L 33001:localhost:33001 chess@141.37.74.145
```

| Forwarded port | Remote target | Service |
|---|---|---|
| `localhost:10000` | server `localhost:10000` | Envoy edge (API + WebSocket) |
| `localhost:33001` | server `localhost:33001` | Grafana |

Add this to `~/.ssh/config` to prevent idle disconnects:
```
Host 141.37.74.145
    ServerAliveInterval 30
    ServerAliveCountMax 3
```

### Terminal 2 — verify tunnel

```bash
curl http://localhost:10000/health
# Expected: {"status":"ok"}
```

### Terminal 3 — start the Web UI

```bash
cd apps/web-ui
npm run dev:deployed
# Opens at http://localhost:5173
```

`npm run dev:deployed` loads `apps/web-ui/.env.deployed`, which sets:
- `VITE_API_BASE_URL=http://localhost:10000`
- `VITE_WS_URL=ws://localhost:10000/ws`

The SSH tunnel forwards both HTTP and WebSocket traffic transparently.

---

## Port reference

| Port | Where | What |
|---|---|---|
| 10000 | Server host | Envoy (HTTP API + WebSocket) |
| 33001 | Server host | Grafana |
| 10000 | Developer machine | SSH-forwarded Envoy |
| 33001 | Developer machine | SSH-forwarded Grafana |
| 5173 | Developer machine | Vite dev server (Web UI) |

---

## Rebuilding and redeploying a service

After changing application code:

```bash
# 1. Rebuild the image
docker build -t searchess/game-service:local .

# 2. Import the updated image
k3d image import searchess/game-service:local --cluster searchess-server

# 3. Restart the deployment
kubectl rollout restart deployment/game-service -n searchess

# 4. Watch rollout
kubectl rollout status deployment/game-service -n searchess
```

---

## Troubleshooting

### `curl http://localhost:10000/health` — connection refused (from developer machine)

The SSH tunnel is not open. Start it in Terminal 1.

### `curl http://localhost:10000/health` — 502 (from server)

game-service is not yet healthy. Wait for readiness probe to pass:
```bash
kubectl get pods -n searchess -l app=game-service
kubectl logs -n searchess -l app=game-service --tail=30
```

### `mongo-0` CrashLoopBackOff — Illegal instruction

The image is `mongo:7.0`, which requires AVX. The `uni-server-k3d` overlay must be applied
(not the `local-k3d` overlay). Verify:
```bash
kubectl get statefulset mongo -n searchess -o jsonpath='{.spec.template.spec.containers[0].image}'
# Must print: mongo:4.4
```
If it prints `mongo:7.0`, re-apply the correct overlay:
```bash
kubectl apply -k deployment/k8s/overlays/uni-server-k3d
kubectl rollout restart statefulset/mongo -n searchess
```

### Pod stuck in `Pending`

```bash
kubectl describe pod -n searchess <pod-name>
```
Common causes:
- PVC not bound — check `kubectl get pvc -n searchess`
- Image not found — verify `k3d image list --cluster searchess-server`

### Port 10000 already in use when creating cluster

The old Compose stack is still running. Stop it:
```bash
docker compose down
ss -tlnp | grep 10000   # must be empty before retrying
```

### `k3d: command not found`

k3d is installed in `~/bin`. Add it to the current session:
```bash
export PATH="$HOME/bin:$PATH"
```
Or add this line to `~/.bashrc` / `~/.bash_profile` so it persists.

### Frontend WebSocket disconnects immediately

The SSH tunnel dropped. Re-open it and reload the browser page.
