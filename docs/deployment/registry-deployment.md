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

| Tag | Format | Stability | Role |
|---|---|---|---|
| Immutable SHA tag | `sha-<7-char-git-sha>` | Stable — exact commit | **Deployment source of truth.** Written into the Kustomize overlay by CI. |
| Convenience tag | `main-latest` | Floating — latest build on `main` | Reference only. Never used as the deployment tag in the overlay. |

The GitHub Actions workflow (`.github/workflows/build-images.yml`) pushes both tags on
every push to the `main` branch. The overlay always references `sha-<7-char-git-sha>`,
not `main-latest`.

---

## CI/CD workflow boundaries

Understanding which commits trigger image builds and which do not is critical for
operating the deployment pipeline without unnecessary canary promotions.

| Commit type | Image rebuild? | Argo CD action |
|---|---|---|
| Application code change (`apps/**`, `modules/**`) | **Yes** — all four images rebuilt | Detects OutOfSync (new sha-* tag in overlay); auto-syncs |
| Dockerfile change | **Yes** | Same as above |
| `build.sbt` / `project/**` change | **Yes** | Same as above |
| Kubernetes manifest change (`deployment/k8s/**`) | **No** | Detects OutOfSync (manifest changed in Git); auto-syncs |
| Argo CD config change (`deployment/argocd/**`) | **No** | No sync needed (not managed by Argo CD Application) |
| Documentation change (`docs/**`) | **No** | Nothing |
| Evidence file change | **No** | Nothing |
| Overlay kustomization.yaml (written by CI) | **No** | Detects OutOfSync; auto-syncs |

**Consequence for Argo Rollouts:**  
`python-ai-service` enters a canary pause **only when its image tag changes** — i.e.,
only after an application code change triggers a build and CI writes a new `sha-*` tag
into the overlay. A pure infrastructure commit (adding a new ConfigMap, tweaking a
Grafana dashboard) causes Argo CD to sync the changed manifest with no Rollout
triggered, because the `Rollout` object's image tag does not change.

**Manual override:**  
`workflow_dispatch` triggers the full image rebuild regardless of what files changed.
Use this when you need to force a rebuild without a code change (e.g. to pick up a
base-image security patch).

---

## GitHub Actions workflow

Trigger: push to `main` branch **only when app code, build files, or Dockerfiles
change** (see paths filter below), or manual dispatch via `workflow_dispatch`.

Paths that trigger an image build:

```
apps/**
modules/**
project/**
build.sbt
**/Dockerfile
Dockerfile*
.github/workflows/build-images.yml
```

All other paths — including `docs/**`, `deployment/k8s/**`, `deployment/argocd/**`,
`deployment/server/**` — do **not** trigger the workflow.

Jobs:
- **build-scala-services** — matrix build for `game-service`, `history-service`,
  `ai-service` using their respective Dockerfiles in the `searchess` repo.
- **build-python-ai-service** — checks out the sibling `arutepsu/searchess-ai-service`
  repository and builds from its `Dockerfile`.
- **update-overlay** — runs after both build jobs succeed. Installs kustomize, updates
  the four service image `newTag` fields in
  `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` to
  `sha-<SHORT_SHA>`, validates the rendered overlay, and commits the change back to
  `main`. Argo CD then detects `OutOfSync` and auto-syncs.

All images are pushed with `sha-<7-char-git-sha>` (deployment tag) and `main-latest`
(convenience tag).

### Infinite loop prevention

The overlay update commit targets only `kustomization.yaml`. Three independent layers
prevent it from re-triggering the build workflow:

1. `GITHUB_TOKEN` pushes do not trigger `push` workflows (GitHub built-in protection).
2. `kustomization.yaml` is not listed in the `paths` filter, so the commit never matches
   the trigger condition.
3. `[skip ci]` in the commit message signals CI systems to skip the run.

### GHCR authentication (workflow)

The workflow uses `GITHUB_TOKEN` (automatically provided by Actions) with
`permissions: contents: write, packages: write`. No additional secret is needed for
public repos.

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

## Deploying after a CI image push (GitOps flow)

After a merge/push to `main`, the full GitOps flow is:

```
push/merge to main (app code / Dockerfile change)
  → GitHub Actions builds images
  → pushes sha-<git-sha> + main-latest to GHCR
  → commits kustomization.yaml update (sha-<git-sha> tag) to main
  → Argo CD detects OutOfSync → auto-syncs (selfHeal: true, prune: false)
  → python-ai-service Rollout enters canary progression (paused at 20%)
  → operator promotes Rollout when ready:

      kubectl argo rollouts promote python-ai-service -n searchess

  → verify Synced + Healthy:
      kubectl get application searchess -n argocd
      curl http://localhost:10000/health   # (via SSH tunnel)
```

For pure infrastructure changes (`deployment/k8s/**`), Argo CD detects and auto-syncs
the manifest change without a CI image build. No Rollout is triggered.

Do not use `kubectl rollout restart` on the floating `main-latest` tag — that bypasses
the GitOps audit trail. Always deploy via Argo CD sync after CI updates the overlay.

---

## Rollback

**Standard rollback — revert the image-tag commit:**

```bash
# Find the commit that updated the tags:
git log --oneline deployment/k8s/overlays/uni-server-registry/kustomization.yaml

# Revert it:
git revert <image-tag-update-commit>
git push origin main
# Argo CD detects OutOfSync and auto-syncs (selfHeal: true)
```

**Rollback to a release tag:**

```bash
# Update targetRevision in deployment/argocd/searchess-application.yaml
# to the desired tag (e.g. v0.1.0-gitops), apply, then sync:
kubectl apply -f deployment/argocd/searchess-application.yaml
argocd app sync searchess
```

**Emergency fallback (Argo CD unavailable):**

```bash
bash deployment/server/deploy-server-registry.sh <previous-sha>
```

---

## Comparison: manual vs. registry overlays

| | `uni-server-k3d` | `uni-server-registry` |
|---|---|---|
| Overlay | `deployment/k8s/overlays/uni-server-k3d` | `deployment/k8s/overlays/uni-server-registry` |
| imagePullPolicy | `Never` | `IfNotPresent` (base default) |
| Image source | `searchess/*:local` (k3d imported) | `ghcr.io/arutepsu/searchess-*:sha-<git-sha>` (written by CI) |
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
| Branch | `main` |
| Path | `deployment/k8s/overlays/uni-server-registry` |
| Destination | `https://kubernetes.default.svc`, namespace `searchess` |
| Sync | **Auto** — `selfHeal: true` (Argo CD applies GitOps state automatically); Rollout promotion remains manual |
| Prune | **Disabled** (`prune: false`) — removed resources are not auto-deleted |
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

---

## Argo Rollouts — python-ai-service canary

`python-ai-service` uses an Argo Rollouts canary `Rollout` in the `uni-server-registry`
overlay. `local-k3d` and `uni-server-k3d` continue to use the base `apps/v1 Deployment`
unchanged.

### Prerequisites (one-time server setup)

```bash
# Install Argo Rollouts controller into the argo-rollouts namespace:
kubectl create namespace argo-rollouts
kubectl apply -n argo-rollouts \
  -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml

# Install the kubectl plugin (on the operator's machine):
# macOS/Linux:
curl -LO https://github.com/argoproj/argo-rollouts/releases/latest/download/kubectl-argo-rollouts-linux-amd64
chmod +x kubectl-argo-rollouts-linux-amd64
sudo mv kubectl-argo-rollouts-linux-amd64 /usr/local/bin/kubectl-argo-rollouts
```

### Canary strategy

`python-ai-service` runs **5 replicas** and uses replica-based canary weighting.
Weight percentages are approximated by scaling pod counts — there is no L7 traffic
splitting. `kube-proxy` distributes connections across all ready pods (stable + canary)
with equal weight; the effective traffic split tracks the pod ratio.

```
replicas: 5   maxSurge: 1   maxUnavailable: 0

Stable pods: searchess/python-ai-service:<previous-sha>
Canary pods: searchess/python-ai-service:<new-sha>

Step 1: setWeight 20  → ceil(5 × 0.20) = 1 canary pod,  4 stable pods  (~20% traffic)
Step 2: pause {}      → INDEFINITE — operator must promote or abort
Step 3: setWeight 50  → ceil(5 × 0.50) = 3 canary pods, 3 stable pods  (~50% traffic)
Step 4: pause {}      → INDEFINITE — operator must promote or abort
Full:   100%          → all 5 pods running the new image, stable ReplicaSet winds down
```

`maxSurge: 1` allows at most one extra pod during the transition. `maxUnavailable: 0`
keeps all current pods running while the new canary pod starts and passes its readiness
probe. This prevents any capacity drop during promotion.

This is not exact L7 traffic splitting. The Service selector (`app=python-ai-service`)
covers both stable and canary pods. Traffic distribution depends on kube-proxy
load-balancing across all ready pods and is proportional to pod counts, not measured
at the request level. A traffic router or service mesh is required for precise
percentage-based L7 splitting.

**Resource note — university VM (4 GB RAM, shared):**
5 replicas × 256 Mi request = 1280 Mi requested memory. Each pod may spike up to
1000 Mi (torch limit). Running all 5 simultaneously during a canary step uses up to
5 GB, which exceeds the VM's 4 GB. In practice the fake/random inference backend uses
far less (~50–100 Mi per pod). Monitor with `kubectl top pods -n searchess` before
increasing replicas or switching to the supervised backend.

### Operator commands during a rollout

```bash
# Watch live status:
kubectl argo rollouts get rollout python-ai-service -n searchess --watch

# Promote to the next step (advance past the current pause):
kubectl argo rollouts promote python-ai-service -n searchess

# Skip all remaining steps and complete immediately:
kubectl argo rollouts promote --full python-ai-service -n searchess

# Abort the rollout (rolls back to the stable revision):
kubectl argo rollouts abort python-ai-service -n searchess

# Undo (sets desired image back to the previous stable):
kubectl argo rollouts undo python-ai-service -n searchess
```

### Argo CD interaction

Argo CD manages the `Rollout` object (image tag, replica count, strategy). It does NOT
manage individual pod replica sets created by the Argo Rollouts controller — those are
controller-owned. After Argo CD syncs a new image tag, the Rollout controller drives
the canary progression automatically; Argo CD will show `Progressing` health until the
rollout completes or is manually promoted.

Do not force-sync mid-rollout unless you intend to re-apply the manifest (this restarts
the rollout from step 1).

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

## Argo CD drift — k3s-managed StatefulSet fields

After adding the Kubernetes-defaulted fields to the base manifests, the three
StatefulSets still showed `OutOfSync`. Inspecting `managedFields` on the live objects
revealed a second manager:

| Resource | Managers |
|---|---|
| StatefulSet/mongo | `argocd-controller` (Apply), `kubectl-set` (Update), `k3s` (Update) |
| StatefulSet/postgres | `argocd-controller` (Apply), `k3s` (Update) |
| StatefulSet/redis | `argocd-controller` (Apply), `k3s` (Update) |

**Root cause:** k3s writes additional fields (or re-writes defaulted fields) under its
own manager entry after `argocd-controller` applies the manifest. Argo CD detects the
gap between what it applied and what k3s wrote back, and reports persistent drift.

**Fix (round 1):** Added `ignoreDifferences` rules to
`deployment/argocd/searchess-application.yaml` scoped to the three StatefulSets and the
`k3s` manager only — covering all k3s-managed fields tracked in `managedFields`.

**Fix (round 2):** Comparing desired vs live `volumeClaimTemplates` revealed four
additional fields written by k3s onto the embedded PVC templates that are not tracked
in `managedFields` and therefore not covered by `managedFieldsManagers`:

| Field | Where fixed |
|---|---|
| `apiVersion: v1` | added to desired manifest |
| `kind: PersistentVolumeClaim` | added to desired manifest |
| `metadata.creationTimestamp: null` | added to desired manifest |
| `spec.volumeMode: Filesystem` | added to desired manifest |
| `status.phase: Pending` | ignored via `jqPathExpressions` |

`status` is a runtime field that Kubernetes writes onto embedded PVC templates after
creation. It cannot be declared in a desired manifest and is not tracked in
`managedFields`, so it must be excluded via a `jqPathExpression`.

The complete `ignoreDifferences` block after both rounds:

```yaml
ignoreDifferences:
  - group: apps
    kind: StatefulSet
    name: mongo       # same shape for postgres and redis
    namespace: searchess
    managedFieldsManagers:
      - k3s
    jqPathExpressions:
      - ".spec.volumeClaimTemplates[].status"
```

**Scope limits:**
- Only `mongo`, `postgres`, and `redis` StatefulSets are targeted — not all StatefulSets.
- Only the `k3s` manager is ignored. `kubectl-set` is intentionally **not** ignored so
  that manual image overrides applied with `kubectl set image` remain visible as drift.
- The `jqPathExpressions` entry ignores only `volumeClaimTemplates[*].status`; no other
  fields, pod templates, Deployments, Services, ConfigMaps, or Secrets are affected.
- Auto-sync and prune remain disabled.

---

## Mongo 4.4 note

Same constraint as `uni-server-k3d`: the university VM does not expose AVX CPU
instructions to guest VMs. Both overlays pin `mongo` to `4.4` via the Kustomize
`images` block. The base manifests keep `mongo:7.0` unchanged.
