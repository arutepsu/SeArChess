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
the six application images.

`lichess-bot` is `replicas: 0` in base.  It must be scaled up deliberately
after real secrets are patched in.

### Overlay differences

| Aspect | local-k3d | uni-server-k3d |
|---|---|---|
| `searchess/web-ui` image | `:local` | `:local` |
| `searchess/game-service` image | `:local` | `:local` |
| `searchess/history-service` image | `:local` | `:local` |
| `searchess/ai-service` image | `:local` | `:local` |
| `searchess/python-ai-service` image | `:local` | `:local` |
| `searchess/lichess-bot` image | `:local` | `:local` |
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
| `searchess/lichess-bot:local` | `docker build -f Dockerfile.lichess-bot -t searchess/lichess-bot:local .` | `Dockerfile.lichess-bot` |
| `searchess/web-ui:local` | `docker build -t searchess/web-ui:local apps/web-ui/` | `apps/web-ui/Dockerfile` |
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
- `external-game-bot-api-key: ""`
- `lichess-bot-token: ""`

This is identical to the Docker Compose dev defaults.  It is intentionally not
a real secret — do not deploy either overlay on an internet-accessible host.

### Real secrets (never committed)

After `kubectl apply`, patch the live Secret for any service that needs a real
token:

```powershell
# PowerShell (local-k3d)
kubectl patch secret searchess-secrets -n searchess `
  --type=merge `
  -p '{"stringData":{"lichess-bot-token":"REAL_TOKEN","external-game-bot-api-key":"REAL_KEY"}}'
```

```bash
# bash (uni server)
kubectl patch secret searchess-secrets -n searchess \
  --type=merge \
  -p '{"stringData":{"lichess-bot-token":"REAL_TOKEN","external-game-bot-api-key":"REAL_KEY"}}'
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
docker build -f Dockerfile.lichess-bot -t searchess/lichess-bot:local    .
docker build -t searchess/web-ui:local apps/web-ui/
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
  searchess/lichess-bot:local `
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

## Lichess bot controlled test

The bot is `replicas: 0` in base — it will not start automatically.

### Prerequisites

Real secrets must be patched before scaling up:
```powershell
kubectl patch secret searchess-secrets -n searchess `
  --type=merge `
  -p '{"stringData":{"lichess-bot-token":"REAL_TOKEN","external-game-bot-api-key":"REAL_KEY"}}'
```

### Scale up

```powershell
# Local
kubectl scale deployment lichess-bot -n searchess --replicas=1
kubectl logs -n searchess deployment/lichess-bot -f
```

```bash
# Uni server
kubectl scale deployment lichess-bot -n searchess --replicas=1
kubectl logs -n searchess deployment/lichess-bot -f
```

Watch for: successful authentication log line from Lichess.

### Scale back down

```powershell
kubectl scale deployment lichess-bot -n searchess --replicas=0
```

Re-applying the overlay also resets to 0 replicas (`replicas: 0` is in base).

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

**Problem:** `searchess/lichess-bot:local` was absent from the import list.
The overlay tags and patches it as a local image, so the cluster would fail
to pull it if ever scaled up.

**Fix:** Added `searchess/lichess-bot:local` to the `k3d image import` call
and updated the verification grep to cover `lichess-bot` and `web-ui`.

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
- **Bot replicas: 0 in base** — intentional safety default; controlled scaling
  is the documented procedure.
- **kubeconfig 127.0.0.1 workaround** — this is a local `kubectl config`
  command that touches only the operator's local kubeconfig file.  It is not
  a repo change and is not applied to any server manifest.
- **uni-server-registry overlay** — out of scope for this session; it targets
  the Argo CD / GHCR production path and uses SealedSecrets.
