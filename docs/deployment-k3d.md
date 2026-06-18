# Searchess k3d Deployment Reference

Two deployment modes share the same base manifests (`deployment/k8s/base`) and
differ only in their Kustomize overlay and k3d cluster config.

| Concern | local-k3d (Windows dev) | uni-server-k3d (university VM) |
|---|---|---|
| k3d config | `deployment/k3d/cluster.yaml` | `deployment/k3d/server-cluster.yaml` |
| Kustomize overlay | `deployment/k8s/overlays/local-k3d` | `deployment/k8s/overlays/uni-server-k3d` |
| Cluster name | `searchess` | `searchess-server` |
| Envoy host port | `10000` | `10000` |
| Grafana host port | `3001` | `33001` |
| Mongo version | `7.0` (base default) | `4.4` (no AVX on uni VM) |
| kubeconfig workaround | `127.0.0.1` instead of `host.docker.internal` | none needed (Linux) |
| Deploy script | manual (PowerShell, see below) | `deployment/server/deploy-server-k3d.sh` |
| Verify script | manual (see below) | `deployment/server/verify-server-k3d.sh` |

---

## Current structure

### Shared characteristics

Both overlays set `imagePullPolicy: Never` on all six application Deployments
and tag all application images `:local`.  Images must be imported into k3d
before `kubectl apply`.

Services that come from public registries (Keycloak, Postgres, Mongo, Redis,
Envoy, Prometheus, Grafana, Tempo, OpenTelemetry Collector) are pulled
normally from Docker Hub / Quay.  `imagePullPolicy: Never` applies only to
the application images.

`bot-service` is `replicas: 0` in base as a shared safety default.
`local-k3d-fast` patches it to `replicas: 1`, so local HumanVsDeployedBot games
start the bot worker automatically.  `BOT_WORKER_API_KEY` comes from the
`bot-worker-api-key` Secret key and must be non-empty; if it is empty,
bot-service fails fast on startup.  The bot plays games in the Searchess Web UI
by calling Searchess APIs; it does NOT interact with lichess.org challenges.

### Overlay differences

| Aspect | local-k3d | uni-server-k3d |
|---|---|---|
| `searchess/web-ui` image | `:local` | `:local` |
| `searchess/game-service` image | `:local` | `:local` |
| `searchess/history-service` image | `:local` | `:local` |
| `searchess/ai-service` image | `:local` | `:local` |
| `searchess/python-ai-service` image | `:local` | `:local` |
| `searchess/bot-service` image | `:local` | `:local` |
| `mongo` image | `7.0` (base) | `4.4` (overlay override) |
| Grafana Service type | LoadBalancer | LoadBalancer |
| Envoy Service type | LoadBalancer | LoadBalancer |
| Secret patch | `patches/secret-dev.yaml` | `patches/secret-dev.yaml` (identical) |

Keycloak base settings (both overlays inherit from base):
- `quay.io/keycloak/keycloak:26.6.2`
- `KC_HTTP_MANAGEMENT_RELATIVE_PATH=/` (health probes on port 9000 at `/health/*`)
- `KC_HTTP_RELATIVE_PATH=/auth`
- `requests: 512Mi / limits: 1Gi`
- `startupProbe: /health/started` on port 9000

---

## Image build/import matrix

| Image | Build command (from repo root) | Source |
|---|---|---|
| `searchess/game-service:local` | `docker build -f Dockerfile -t searchess/game-service:local .` | `Dockerfile` |
| `searchess/history-service:local` | `docker build -f Dockerfile.history -t searchess/history-service:local .` | `Dockerfile.history` |
| `searchess/ai-service:local` | `docker build -f Dockerfile.ai -t searchess/ai-service:local .` | `Dockerfile.ai` |
| `searchess/bot-service:local` | `docker build -f Dockerfile.bot-service -t searchess/bot-service:local .` | `Dockerfile.bot-service` |
| `searchess/web-ui:local` | `docker build -t searchess/web-ui:local frontend/web-ui/` | `frontend/web-ui/Dockerfile` |
| `searchess/python-ai-service:local` | `docker load -i python-ai-service-local.tar` | pre-built tar |

The web-ui Dockerfile defaults (`VITE_KEYCLOAK_URL=/auth`, same-origin API/WS)
are correct for both k3d modes and require no build args.

The python-ai-service tar is pre-built.  After `docker load` verify the tag is
`:local`:
```bash
docker images searchess/python-ai-service
```
If the loaded tag differs, retag it:
```bash
docker tag searchess/python-ai-service:<loaded-tag> searchess/python-ai-service:local
```

---

## Secrets handling

### Base secret (repo)

`deployment/k8s/base/secret-searchess.yaml` contains PLACEHOLDER values only.

### Overlay dev patch (repo, safe to commit)

Both overlays apply `patches/secret-dev.yaml`:
- `postgres-password: "searchess"`
- `grafana-admin-password: "admin"`
- `migration-admin-token: ""`
- `bot-worker-api-key: ""`  backs `BOT_WORKER_API_KEY` in game-service and bot-service

This is identical to the Docker Compose dev defaults.  It is intentionally not
a real secret — do not deploy either overlay on an internet-accessible host.

### Real secrets (never committed)

For `local-k3d-fast`, patch the live Secret before or immediately after
`kubectl apply`; the overlay starts bot-service automatically.  HumanVsDeployedBot
uses `bot-worker-api-key` as `BOT_WORKER_API_KEY`.

```powershell
# PowerShell (local-k3d)
kubectl patch secret searchess-secrets -n searchess `
  --type=merge `
  -p '{"stringData":{"bot-worker-api-key":"REAL_KEY"}}'
```

```bash
# bash (uni server)
kubectl patch secret searchess-secrets -n searchess \
  --type=merge \
  -p '{"stringData":{"bot-worker-api-key":"REAL_KEY"}}'
```

Re-applying the overlay will reset patched secrets back to the dev defaults.
Re-patch after any `kubectl apply -k`.

---

## Local Windows k3d workflow

Run all commands from the repo root in PowerShell.

### Step 0 — Delete stale cluster (if recreating)

```powershell
k3d cluster delete searchess
```

### Step 1 — Create cluster

```powershell
k3d cluster create --config deployment/k3d/cluster.yaml
```

### Step 2 — Fix kubeconfig (Windows/VPN workaround)

After cluster creation, kubeconfig points to `https://host.docker.internal:<port>`.
On Windows with VPN, `host.docker.internal` may not resolve.  Fix locally:

```powershell
$port = (docker port k3d-searchess-serverlb 6443/tcp) -replace '.*:',''
kubectl config set-cluster k3d-searchess --server="https://127.0.0.1:$port"
```

Verify:
```powershell
kubectl get nodes
```

Expected output: one node in `Ready` state.

This command changes your local kubeconfig only.  It is not committed to the
repo and does not affect the uni-server deployment.

### Step 3 — Build images (skip if already built and present)

```powershell
docker build -f Dockerfile            -t searchess/game-service:local    .
docker build -f Dockerfile.ai         -t searchess/ai-service:local      .
docker build -f Dockerfile.history    -t searchess/history-service:local .
docker build -f Dockerfile.bot-service -t searchess/bot-service:local    .
docker build -t searchess/web-ui:local frontend/web-ui/
docker load -i python-ai-service-local.tar
# Retag if loaded tag is not :local
docker tag searchess/python-ai-service:latest searchess/python-ai-service:local
```

### Step 4 — Import images into k3d

```powershell
k3d image import `
  searchess/game-service:local `
  searchess/history-service:local `
  searchess/ai-service:local `
  searchess/python-ai-service:local `
  searchess/web-ui:local `
  searchess/bot-service:local `
  --cluster searchess
```

### Step 5 — Apply overlay

```powershell
kubectl apply -k deployment/k8s/overlays/local-k3d
```

### Step 6 — Watch pods come up

```powershell
kubectl get pods -n searchess -w
```

JVM services (game-service, history-service, ai-service) take 60–90 s on first
boot.  Keycloak takes up to 5 minutes (realm import + production-mode startup).

---

## Uni-server k3d workflow

Run on the university VM in bash.  `k3d`, `kubectl`, and `docker` must be
installed and available (the server scripts prepend `$HOME/bin` to PATH).

### Step 0 — Load images into Docker (before cluster creation)

```bash
docker load -i <path>/python-ai-service-local.tar
docker tag searchess/python-ai-service:latest searchess/python-ai-service:local
```

Build or transfer the JVM images if not already present:
```bash
docker images searchess/
```

### Step 1 — Create cluster and apply overlay

```bash
bash deployment/server/deploy-server-k3d.sh
```

This script:
1. Creates `searchess-server` cluster if it does not exist
2. Merges kubeconfig
3. Applies `deployment/k8s/overlays/uni-server-k3d`

### Step 2 — Import images

```bash
bash deployment/server/import-images.sh
```

### Step 3 — Watch pods

```bash
kubectl get pods -n searchess -w
```

---

## Validation commands

### Local Windows

```powershell
# Cluster node
kubectl get nodes

# All pods
kubectl get pods -n searchess

# Envoy edge health (game-service behind Envoy)
Invoke-RestMethod http://localhost:10000/health

# Grafana
Invoke-RestMethod http://localhost:3001/api/health
```

### Uni server (bash / curl)

```bash
kubectl get nodes
kubectl get pods -n searchess

curl -fsS http://localhost:10000/health
curl -fsS http://localhost:33001/api/health
```

Or run the bundled verify script:
```bash
bash deployment/server/verify-server-k3d.sh
```

### Keycloak

Keycloak is accessible via `kubectl port-forward` or the Envoy route at `/auth`:

```powershell
# Local — via Envoy (browser)
Start-Process "http://localhost:10000/auth"

# Or via port-forward directly to Keycloak pod (admin console)
kubectl port-forward -n searchess deployment/keycloak 18000:8080
Start-Process "http://localhost:18000/auth/admin"
```

Expected: realm `searchess` exists, client `searchess-web` exists,
demo user `demo/demo` can log in.

---

## bot-service local-k3d-fast startup

`bot-service` is `replicas: 0` in base, but `local-k3d-fast` patches it to
`replicas: 1`.  It starts automatically when the overlay is applied, using
`searchess/bot-service:local` with `imagePullPolicy: Never`.

It plays games inside the Searchess Web UI by calling Searchess APIs.  It does
NOT interact with lichess.org challenges.  A linked Lichess account is an
eligibility prerequisite checked by user-service, but the bot game itself is
played entirely within Searchess.

### Prerequisites

Patch the real API key before applying the overlay, or patch it immediately
after apply and restart the bot-service rollout:
```powershell
kubectl patch secret searchess-secrets -n searchess `
  --type=merge `
  -p '{"stringData":{"bot-worker-api-key":"REAL_KEY"}}'
```

`bot-worker-api-key` backs `BOT_WORKER_API_KEY` in both game-service and
bot-service.  It must be non-empty.  If `BOT_WORKER_API_KEY` is empty,
bot-service fails fast on startup.  Game-service temporarily accepts legacy
`EXTERNAL_GAME_BOT_API_KEY` as an environment-variable fallback for one
transition period, but Kubernetes and Compose should use `BOT_WORKER_API_KEY`.

Bot-service uses `BOT_MOVE_TIMEOUT_MILLIS` for its internal HTTP calls to
game-service and AI service, and sends the same timeout in the AI request
limits.  The local default is `5000` ms.  If AI service is unavailable or too
slow, bot-service intentionally keeps the game moving by falling back to the
first legal move returned by game-service; look for `AI unavailable, falling
back to first legal move` in `kubectl logs`.

Game-service runs a lease sweeper every
`BOT_TURN_LEASE_SWEEP_INTERVAL_SECONDS` seconds, default `30`, so a bot task
leased by a crashed worker is returned to `Pending` after its lease expires.
If an expired task has reached `BOT_TURN_MAX_ATTEMPTS`, default `5`, the sweeper
marks it `Failed` instead of requeueing it.

### Validate startup

```powershell
kubectl kustomize deployment/k8s/overlays/local-k3d-fast
kubectl apply -k deployment/k8s/overlays/local-k3d-fast
kubectl rollout status deployment/bot-service -n searchess --timeout=3m
kubectl logs -n searchess deployment/bot-service --tail=50
kubectl logs -n searchess deployment/game-service --tail=100
```

Expected log line:

```text
[BotWorker] Starting. gameServiceUrl=http://game-service:8080 aiServiceUrl=http://ai-service:8765 actorId=searchess-bot
```

Starting a HumanVsDeployedBot game and making a human move should create a
`bot_turn_task`; bot-service should lease it and submit a bot move without
manual scaling.

Expected task lifecycle:

- `Pending` after the human move creates a bot task.
- `Leased` while bot-service is computing/submitting the bot move.
- `Completed` after game-service accepts the bot move.
- `Pending` again if a lease expires before max attempts.
- `Failed` if an expired lease has reached `BOT_TURN_MAX_ATTEMPTS`.

Useful game-service log events:

- `bot_turn_task_created`
- `bot_turn_task_leased`
- `bot_turn_move_submitted`
- `bot_turn_task_completed`
- `bot_turn_expired_leases_swept`
- `bot_turn_task_failed`

---

## Recommended repo changes (applied in this session)

### Bug fix — uni-server-k3d/kustomization.yaml

**Problem:** `searchess/web-ui` was missing from the `images:` section and
from the `imagePullPolicy: Never` patches.  With no image override and no pull
policy patch, web-ui would use `searchess/web-ui:latest` with
`imagePullPolicy: IfNotPresent` and attempt a registry pull — which fails in
the air-gapped k3d environment.

**Fix:** Added `searchess/web-ui: local` to the `images:` block and added
the `imagePullPolicy: Never` patch for the `web-ui` Deployment.

**Scope:** uni-server-k3d overlay only.  This is a correctness fix, not a
Windows-specific change.

### Bug fix — deployment/server/import-images.sh

**Problem:** `searchess/bot-service:local` was absent from the import list.
The overlay tags and patches it as a local image, so the cluster would fail
to pull it if ever scaled up.

**Fix:** Added `searchess/bot-service:local` to the `k3d image import` call
and updated the verification grep to cover `bot-service` and `web-ui`.

**Scope:** server import script only.

---

## Things intentionally not changed

- **server-cluster.yaml** — not changed for any Windows quirk.  Grafana port
  `33001` is a server-side decision, not a local one.
- **uni-server-k3d/patches/secret-dev.yaml** — identical to local-k3d by
  design; both are dev-only defaults with no real tokens.
- **Mongo 7.0 in base** — local-k3d inherits Mongo 7.0 from base; this is
  fine on a modern Windows host.  Only the uni VM needs the 4.4 pin.
- **Keycloak base settings** — `KC_HTTP_MANAGEMENT_RELATIVE_PATH=/` and
  health probe configuration are in base because both environments run
  Keycloak 26.6.2 with the same requirements.
- **Bot replicas: 0 in base** — intentional safety default for shared/server
  manifests. `local-k3d-fast` opts in with `replicas: 1` because
  HumanVsDeployedBot is an available local product feature.
- **kubeconfig 127.0.0.1 workaround** — this is a local `kubectl config`
  command that touches only the operator's local kubeconfig file.  It is not
  a repo change and is not applied to any server manifest.
- **uni-server-registry overlay** — out of scope for this session; it targets
  the Argo CD / GHCR production path and uses SealedSecrets.
