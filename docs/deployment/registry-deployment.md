# Registry-Based Deployment

This document covers deploying Searchess on the university server k3d cluster using
pre-built images from GitHub Container Registry (GHCR) instead of manually importing
locally-built Docker archives.

---

## Why registry vs. manual import

| Concern | Manual import (`uni-server-k3d`) | Registry (`uni-server-registry`) |
|---|---|---|
| Image source | Built and imported on-demand | Built by CI, pulled from GHCR |
| Deploy step | `import-images.sh` + `deploy-server-k3d.sh` | `deploy-server-registry.sh` |
| Reproducibility | Depends on local build environment | Tagged by git SHA — fully reproducible |
| Rollback | Re-import previous archive | `deploy-server-registry.sh <sha>` |
| Requires JDK/sbt on server | Yes (or transfer tarballs) | No |
| Network required on server | No | Yes (GHCR pull) |

Use `uni-server-k3d` when you need to test uncommitted changes.
Use `uni-server-registry` for all validated deployments to the shared server.

---

## GHCR image names

| Service | Image |
|---|---|
| game-service | `ghcr.io/arutepsu/searchess-game-service` |
| history-service | `ghcr.io/arutepsu/searchess-history-service` |
| ai-service | `ghcr.io/arutepsu/searchess-ai-service` |
| python-ai-service | `ghcr.io/arutepsu/searchess-python-ai-service` |

Third-party images (Envoy, Postgres, Mongo, Redis, Prometheus, Grafana) continue to be
pulled from Docker Hub.

---

## Tag strategy

| Tag | Meaning | Stability |
|---|---|---|
| `<git-sha>` (7-char short SHA) | Immutable — exact commit | Stable, use for prod-like deploys |
| `performance-latest` | Floating — latest passing build on `performance` branch | Convenient for rapid iteration |

The GitHub Actions workflow (`.github/workflows/build-images.yml`) pushes both tags on
every push to the `performance` branch.

---

## GitHub Actions workflow

Trigger: push to `performance` branch, or manual dispatch via `workflow_dispatch`.

Jobs:
- **build-scala-services** — matrix build for `game-service`, `history-service`, `ai-service`
  using their respective Dockerfiles in the `searchess` repo.
- **build-python-ai-service** — checks out the sibling `arutepsu/searchess-ai-service`
  repository and builds from its `Dockerfile`.

All images are pushed with both the short SHA tag and `performance-latest`.

### GHCR authentication (workflow)

The workflow uses `GITHUB_TOKEN` (automatically provided by Actions) with
`permissions: packages: write`. No additional secret is needed for public repos.

If `searchess-ai-service` is private, add a Personal Access Token (PAT) with
`read:packages` scope as the repository secret `AI_SERVICE_PAT`, and uncomment the
`token:` line in the `build-python-ai-service` job checkout step.

---

## GHCR authentication (server pull)

k3d nodes pull images at pod start. To authenticate with GHCR:

```bash
# On the university server — one-time setup:
docker login ghcr.io -u <github-username> -p <PAT-with-read:packages>
```

The k3d nodes inherit Docker's credential store, so images marked `IfNotPresent`
will be pulled from GHCR on first use and cached locally thereafter.

Alternatively, export `GHCR_USER` and `GHCR_TOKEN` before running the deploy script —
it will create a Kubernetes `docker-registry` secret in the `searchess` namespace
automatically.

---

## Deploying

### Initial deploy (from repo root on the server)

```bash
export PATH="$HOME/bin:$PATH"

# Option A — use the floating 'performance-latest' tag:
bash deployment/server/deploy-server-registry.sh

# Option B — pin to a specific git SHA:
bash deployment/server/deploy-server-registry.sh abc1234
```

The script will:
1. Create the `searchess-server` k3d cluster if it does not already exist.
2. Merge the cluster's kubeconfig into `~/.kube/config`.
3. Optionally create a GHCR pull secret in the `searchess` namespace.
4. Apply `deployment/k8s/overlays/uni-server-registry`.

### Verify

```bash
bash deployment/server/verify-server-registry.sh
```

This checks pod phases, confirms all app images reference `ghcr.io/arutepsu/`,
confirms `mongo` is pinned to `4.4`, and runs the Envoy and Grafana health endpoints.

---

## Rolling update after a new image push

After CI pushes new images to GHCR:

```bash
# Restart deployments to pick up the new 'performance-latest' digest:
kubectl rollout restart deployment/game-service deployment/history-service \
  deployment/ai-service deployment/python-ai-service -n searchess

# Watch rollout:
kubectl rollout status deployment/game-service -n searchess
```

To pin to a specific SHA instead of restarting on the floating tag:

```bash
bash deployment/server/deploy-server-registry.sh <sha>
```

---

## Rollback

```bash
# Find the previous SHA from CI runs or git log:
git log --oneline -10

# Re-deploy the previous commit:
bash deployment/server/deploy-server-registry.sh <previous-sha>
```

---

## Comparison: manual vs. registry overlays

| | `uni-server-k3d` | `uni-server-registry` |
|---|---|---|
| Overlay | `deployment/k8s/overlays/uni-server-k3d` | `deployment/k8s/overlays/uni-server-registry` |
| imagePullPolicy | `Never` | `IfNotPresent` (base default) |
| Image source | `searchess/*:local` (k3d imported) | `ghcr.io/arutepsu/searchess-*:performance-latest` |
| Mongo | `4.4` | `4.4` |
| Deploy script | `deploy-server-k3d.sh` | `deploy-server-registry.sh` |
| Verify script | `verify-server-k3d.sh` | `verify-server-registry.sh` |
| Requires image import | Yes | No |
| Requires GHCR pull access | No | Yes |

Both overlays share the same k3d cluster (`searchess-server`) and the same
port mappings (Envoy 10000, Grafana 33001). They can be switched without
recreating the cluster — just apply a different overlay.

---

## Argo CD GitOps integration

Argo CD provides a GitOps control loop over the `uni-server-registry` overlay.
It runs inside the same k3d cluster in namespace `argocd` and syncs resources
into namespace `searchess` on demand.

| Property | Value |
|---|---|
| Source repo | `https://github.com/arutepsu/SeArChess.git` |
| Branch | `performance` |
| Path | `deployment/k8s/overlays/uni-server-registry` |
| Destination | `https://kubernetes.default.svc`, namespace `searchess` |
| Sync | **Manual** — no auto-sync in this phase |
| Prune | Disabled |
| Exposure | `kubectl port-forward` only — not via LoadBalancer |

### Bootstrap Argo CD

```bash
# From repo root on the server (cluster must already exist):
bash deployment/server/deploy-server-argocd.sh
```

### Access the UI

```bash
# On the server (keep this terminal open):
bash deployment/argocd/port-forward-argocd.sh
# → https://localhost:8080

# From Windows, SSH-tunnel first:
ssh -L 8080:localhost:8080 chess@141.37.74.145
```

### Trigger a manual sync

```bash
# argocd CLI (if installed):
argocd app sync searchess

# Or use the Argo CD web UI → Applications → searchess → Sync
```

### Relationship to the manual workflow

The manual deploy scripts (`deploy-server-registry.sh`, `deploy-server-k3d.sh`) are
**not removed**. They remain as:
- Fallback if Argo CD is not installed or is unhealthy.
- The mechanism for the initial cluster and registry bootstrap (Argo CD is installed
  on top of an already-working registry deployment).

Secrets remain plain Kubernetes Secret patches (`patches/secret-dev.yaml`) until
Sealed Secrets is introduced in a later phase.

See `deployment/argocd/README.md` for the full Argo CD directory reference.

---

## Argo CD drift — StatefulSet default fields

After the first Argo CD sync, `StatefulSet/mongo`, `StatefulSet/postgres`, and
`StatefulSet/redis` remained `OutOfSync` despite the sync operation succeeding and
all pods being `Healthy`.

**Root cause:** Kubernetes defaults several fields on every StatefulSet that were
absent from the Kustomize-rendered manifests. Argo CD diff detected the gap and
reported persistent drift. The defaulted fields are:

- `spec.persistentVolumeClaimRetentionPolicy` (Kubernetes 1.27+)
- `spec.podManagementPolicy`
- `spec.revisionHistoryLimit`
- `spec.updateStrategy`

**Fix:** Added all four fields explicitly to all three base StatefulSet manifests so
the rendered output matches the live state exactly:

```yaml
podManagementPolicy: OrderedReady
revisionHistoryLimit: 10
updateStrategy:
  type: RollingUpdate
  rollingUpdate:
    partition: 0
persistentVolumeClaimRetentionPolicy:
  whenDeleted: Retain
  whenScaled: Retain
```

Files updated: `deployment/k8s/base/mongo/statefulset.yaml`,
`deployment/k8s/base/postgres/statefulset.yaml`,
`deployment/k8s/base/redis/statefulset.yaml`.

The fix flows through all overlays (`local-k3d`, `uni-server-k3d`,
`uni-server-registry`) because it is in the base.

---

## Mongo 4.4 note

Same constraint as `uni-server-k3d`: the university VM does not expose AVX CPU
instructions to guest VMs. Both overlays pin `mongo` to `4.4` via the Kustomize
`images` block. The base manifests keep `mongo:7.0` unchanged.
