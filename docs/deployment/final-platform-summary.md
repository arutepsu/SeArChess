# Searchess — Final Platform Summary

**Date:** 2026-05-25  
**Branch:** main  
**Argo CD status:** Synced + Healthy  

---

## 1. Executive summary

Searchess is a functional chess platform delivered through a professional cloud-native
deployment pipeline running on a shared university k3s server.

**What was built:**

A full GitOps continuous delivery chain: code merged to `main` triggers GitHub Actions,
which builds immutable GHCR images, updates Kustomize overlay tags, and triggers Argo
CD to auto-sync the desired state to the cluster. The AI service uses Argo Rollouts for
progressive canary delivery, with a manual promotion gate before full rollout.

**Why this is production-like:**

- Immutable image tags (`sha-<commit>`) make every deployment fully reproducible.
- Argo CD enforces Git as the single source of truth and automatically heals drift.
- Sealed Secrets encrypts credentials at rest in the Git repository.
- Argo Rollouts provides progressive delivery with an explicit human gate before
  promoting the AI service to full traffic.
- Postgres schema isolation, Redis Streams, and structured observability follow
  standard service design practices.

**What is intentionally not production-grade:**

- No TLS or public domain. Access is via SSH tunnel.
- No service mesh. Traffic splitting is replica-based, not L7-weighted.
- Sealed Secrets uses a self-managed cluster key, not a KMS-backed key.
- `prune: false` — old resources are not auto-deleted.
- Partial distributed tracing: game-service (F1) and history-service (F2) emit spans; ai-service and python-ai-service are not yet instrumented (F3–F4).
- No automated canary analysis (AnalysisTemplate not wired).
- Mongo is pinned to 4.4 due to AVX CPU limitations on the university VM.

---

## 2. Deployment topology

```
GitHub (main)
  │
  ├─ push / PR merge
  │
  ▼
GitHub Actions (.github/workflows/build-images.yml)
  │  builds only changed services; pushes sha-<7-char-commit> tag per service
  │  commits updated kustomization.yaml [skip ci] back to main (only rebuilt tags)
  │
  ▼
GitHub Container Registry (ghcr.io/arutepsu/)
  │  searchess-game-service:sha-*
  │  searchess-history-service:sha-*
  │  searchess-ai-service:sha-*
  │  searchess-python-ai-service:sha-*
  │
  ▼
Argo CD (argocd namespace, university k3d cluster)
  │  watches: github.com/arutepsu/SeArChess.git @ main
  │  path:    deployment/k8s/overlays/uni-server-registry
  │  auto-sync: enabled  |  prune: false  |  selfHeal: true
  │
  ▼
Kustomize overlay: uni-server-registry
  │  base/  +  images block (sha-* tags)  +  SealedSecret patch
  │  +  StatefulSet ignoreDifferences for k3s-managed fields
  │
  ▼
k3d / k3s — university server
  │  namespace: searchess
  │
  ├─ Kubernetes Deployments: envoy, game-service, history-service, ai-service,
  │                           grafana, prometheus, tempo, otel-collector
  ├─ StatefulSets:  postgres, mongo, redis
  ├─ Argo Rollout:  python-ai-service (canary, 5 replicas)
  └─ Sealed Secrets controller → Secret/searchess-secrets
```

---

## 3. Runtime services

| Service | Kind | Image | Port | Role |
|---|---|---|---|---|
| envoy | Deployment | `envoyproxy/envoy:v1.32-latest` | 10000 | Public edge proxy — routes `/api/`, `/ws/`, `/health`, `/admin/migrations` |
| game-service | Deployment | `ghcr.io/arutepsu/searchess-game-service:sha-*` | 8080 | Chess game logic, WebSocket moves, REST API |
| history-service | Deployment | `ghcr.io/arutepsu/searchess-history-service:sha-*` | 8081 | Game history delivery via Redis Streams consumer |
| ai-service | Deployment | `ghcr.io/arutepsu/searchess-ai-service:sha-*` | 8765 | Scala facade — proxies AI move requests to python-ai-service |
| python-ai-service | **Rollout** | `ghcr.io/arutepsu/searchess-python-ai-service:sha-*` | 8765 | Python inference backend — canary-controlled |
| postgres | StatefulSet | `postgres:16` | 5432 | Persistent storage — schemas: `game`, `history` |
| mongo | StatefulSet | `mongo:4.4` | 27017 | Document store (pinned 4.4 — no AVX on university VM) |
| redis | StatefulSet | `redis:7.4-alpine` | 6379 | Redis Streams — history archive delivery channel |
| prometheus | Deployment | `prom/prometheus:v2.55.1` | 9090 | Metrics scrape and storage |
| grafana | Deployment | `grafana/grafana:11.4.0` | 3000 (→ 33001 ext) | Dashboard UI — Prometheus + Tempo datasources |
| tempo | Deployment | `grafana/tempo:2.6.1` | 3200 (query), 4317 (OTLP recv) | Trace storage — receives from otel-collector, queried by Grafana |
| otel-collector | Deployment | `otel/opentelemetry-collector-contrib:0.114.0` | 4317 (gRPC), 4318 (HTTP) | Trace ingestion — receives OTLP from services, forwards to Tempo |

---

## 4. Deployment flow

### CI/CD boundary

The build workflow runs **only** when application code, build files, or Dockerfiles
change. Infrastructure manifest and documentation changes are **not** image-build
triggers — Argo CD syncs them directly.

| Change type | Triggers image build? | Argo CD action |
|---|---|---|
| `apps/<service>/**`, `Dockerfile[.<service>]` | **Yes — that service only** | Sync with updated sha-* tag for that service |
| `modules/**`, `build.sbt`, `project/**` | **Yes — all three Scala services** | Sync with new sha-* tags for game, history, ai |
| `python-ai-service` (code in sibling repo) | **Never on push** — `workflow_dispatch rebuild_python_ai=true` only | Rollout canary triggered only when its tag changes |
| `deployment/k8s/**` (manifests, overlays) | No | Sync changed manifest directly |
| `docs/**`, evidence files | No | Nothing |
| Overlay `kustomization.yaml` (written by CI) | No | Sync updated image tags |

This boundary prevents documentation and infrastructure commits from triggering
unnecessary image rebuilds and Rollout canary pauses.

### App-code delivery path

```
Developer merges PR to main (app code / Dockerfile change)
  │
  ▼
GitHub Actions: build-images.yml  (triggered by paths: apps/**, modules/**, etc.)
  ├─ determine-changes: git diff → per-service build flags
  ├─ builds only services whose tracked paths changed
  │    shared Scala inputs (modules/**, build.sbt, project/**) → all three Scala services
  │    python-ai-service: never on push; workflow_dispatch rebuild_python_ai=true only
  ├─ pushes sha-<git-sha> tag to GHCR      ← immutable, deployment source of truth
  ├─ pushes main-latest tag to GHCR        ← convenience only, never deployed
  ├─ runs kustomize edit set image         ← updates newTag only for rebuilt services
  ├─ validates: kubectl kustomize ...      ← fails fast if render breaks
  └─ commits kustomization.yaml [skip ci]  ← paths filter + GITHUB_TOKEN prevent loop
  │
  ▼
Argo CD detects OutOfSync (kustomization.yaml changed in main)
  │
  ▼ auto-sync (selfHeal: true)
Argo CD applies updated manifests via ServerSideApply
  ├─ Deployments rollout new sha-* pods for each rebuilt Scala service
  └─ Rollout/python-ai-service:
       if its image tag changed (workflow_dispatch rebuild_python_ai=true) → enters canary
       otherwise → remains stable; no canary progression triggered
  │
  ▼
Argo Rollouts controller drives python-ai-service canary:
  │  Step 1: setWeight 20  → 1 canary pod  / 4 stable pods
  │  Step 2: pause {}      → PAUSED — operator must act
  │
  ▼ operator: kubectl argo rollouts promote python-ai-service -n searchess
  │
  │  Step 3: setWeight 50  → 3 canary pods / 3 stable pods
  │  Step 4: pause {}      → PAUSED — operator must act
  │
  ▼ operator: kubectl argo rollouts promote python-ai-service -n searchess
  │
  │  Full: 5 pods on new image, stable ReplicaSet scaled to zero
  │
  ▼
Cluster: Synced + Healthy
```

---

## 5. Argo CD policy

| Setting | Value | Reason |
|---|---|---|
| `targetRevision` | `main` | Stable integration branch; `performance` branch retired after merge |
| `automated.selfHeal` | `true` | Argo CD re-applies Git state if live state drifts (e.g. manual kubectl edits) |
| `automated.prune` | **false** | Resources removed from Git are NOT auto-deleted; operator deletes manually |
| `syncOptions` | `CreateNamespace=true`, `ServerSideApply=true` | Namespace is created if absent; SSA avoids annotation size limits on large ConfigMaps |
| `ignoreDifferences` | StatefulSets mongo/postgres/redis — k3s manager + `volumeClaimTemplates[].status` | k3s writes default fields after apply; ignoring them prevents false drift |
| `timeout.reconciliation` | `60s` + 15s jitter | Default is 3 min; 60s polling detects CI image-tag commits within ~1–2 min. GitHub webhooks would give instant detection but require Argo CD to be publicly reachable — not available on the university server. |

**Why `prune: false`:**  
Accidentally removing a resource from a Kustomize overlay (e.g. during a refactor)
would cause Argo CD to delete running StatefulSets, losing persistent data. Until the
team has confidence in the full resource lifecycle and a tested rollback procedure,
operators review and delete orphaned resources manually.

**Why direct `kubectl apply` should be avoided for application resources:**  
Argo CD's `selfHeal: true` will overwrite any out-of-band change within the next sync
cycle. Direct applies also bypass the GitOps audit trail. Use Git commits and let
Argo CD sync instead.

---

## 6. Rollout policy — python-ai-service canary

`python-ai-service` is the only service managed by Argo Rollouts. All other services
use standard `apps/v1 Deployment`.

```
replicas: 5   maxSurge: 1   maxUnavailable: 0

setWeight 20  →  ceil(5 × 0.20) = 1 canary pod,  4 stable pods
pause {}      →  INDEFINITE — operator must promote or abort
setWeight 50  →  ceil(5 × 0.50) = 3 canary pods, 3 stable pods
pause {}      →  INDEFINITE — operator must promote or abort
100%          →  all 5 pods on new image
```

**This is replica-based canary, not L7 traffic splitting.**  
`Service/python-ai-service` selects all pods with `app=python-ai-service` regardless
of whether they are canary or stable. kube-proxy distributes connections across all
ready pods proportional to pod count. There is no per-request routing.

Exact L7 traffic splitting (e.g. "exactly 20% of requests to canary") would require
adding a traffic router: Istio, Linkerd, NGINX Gateway, or an Envoy-based routing rule.
This is the natural next step for stricter canary control.

**Argo CD health during a rollout:**  
Argo CD reports `Progressing` health while the canary is in flight. This is expected.
The Application returns to `Healthy` once the Rollout reaches 100% or is fully promoted.

**Operator commands:**

```bash
# Watch live status:
kubectl argo rollouts get rollout python-ai-service -n searchess --watch

# Advance past the current pause:
kubectl argo rollouts promote python-ai-service -n searchess

# Skip all remaining steps (immediate full promotion):
kubectl argo rollouts promote --full python-ai-service -n searchess

# Abort — rolls back to the stable revision:
kubectl argo rollouts abort python-ai-service -n searchess

# Undo — resets desired image to the previous stable:
kubectl argo rollouts undo python-ai-service -n searchess
```

---

## 7. Secrets policy

| Layer | Mechanism | Status |
|---|---|---|
| uni-server-registry overlay | `SealedSecret/searchess-secrets` (bitnami.com/v1alpha1) | Active — encrypted at rest in Git |
| Sealed Secrets controller | Decrypts SealedSecret → creates `Secret/searchess-secrets` | Running in `kube-system` |
| Dev overlays (local-k3d, uni-server-k3d) | Plain Secret patch (`patches/secret-dev.yaml`) | Acceptable for local dev; never committed with real values |
| Production secret rotation | Manual re-sealing with `kubeseal` | Not yet automated — future work |

The `patches/secret-dev.yaml` patch present in local and k3d overlays is **not applied**
in `uni-server-registry`. The registry overlay uses only the SealedSecret, which is
the only encrypted credential path committed to the repository.

No plaintext secrets appear in any committed file under `uni-server-registry/`.

---

## 8. Observability

| Capability | Tool | Status |
|---|---|---|
| Metrics collection | Prometheus (namespace-scoped) | Operational |
| Dashboard UI | Grafana (dashboards: domain, HTTP, JVM) | Operational |
| Health endpoints | `/health` on Envoy (port 10000) and python-ai-service | Validated |
| Distributed tracing infrastructure | OpenTelemetry Collector + Tempo | Deployed (Tempo + otel-collector running; Grafana datasource provisioned) |
| Distributed tracing — service spans | OpenTelemetry SDK in services | **Phase 2** — no spans emitted yet; Grafana Tempo will be empty until instrumented |
| Alerting rules | Prometheus AlertManager | Not configured |
| Log aggregation | (none) | Not implemented |

Access Grafana via SSH tunnel:

```bash
ssh -L 33001:localhost:33001 chess@<university-server>
# open: http://localhost:33001
```

---

## 9. Data and messaging

**Postgres (postgres:16):**
- Two isolated schemas: `game` and `history`
- Each schema owned by the `searchess` user
- Schema isolation prevents cross-service table collisions without requiring separate databases

```
 game    | searchess
 history | searchess
```

**Redis (redis:7.4-alpine):**
- Redis Streams used for history archive delivery
- Consumer group: `history-service`
- history-service reads stream entries and persists game archives

**Mongo (mongo:4.4):**
- Document store for existing platform data
- Pinned to 4.4 because the university VM does not expose AVX CPU instructions to
  guest VMs. Mongo 5.0+ requires AVX. `mongo:4.4` is the last release without this
  requirement.
- Kustomize `images` block in `uni-server-registry` and `uni-server-k3d` overrides the
  base `mongo:7.0` tag to `4.4` for all server overlays.

---

## 10. Access model

| Path | Method |
|---|---|
| Application HTTP | SSH tunnel: `ssh -L 10000:localhost:10000 chess@<server>` then `http://localhost:10000` |
| Argo CD UI | SSH tunnel: `ssh -L 8080:localhost:8080 chess@<server>` then `https://localhost:8080` |
| Grafana UI | SSH tunnel: `ssh -L 33001:localhost:33001 chess@<server>` then `http://localhost:33001` |
| Web UI (local dev against deployed backend) | `npm run dev:deployed` from `apps/web-ui/` |

Direct external server ports may be blocked by the university network. The SSH tunnel
remains the standard access path.

No TLS certificate is configured. Argo CD is accessed over a self-signed HTTPS
connection through the tunnel. No public domain is registered for the deployment.

---

## 11. Rollback strategy

**For all services except python-ai-service:**

```bash
# Find the generated image-tag commit:
git log --oneline deployment/k8s/overlays/uni-server-registry/kustomization.yaml

# Revert it:
git revert <image-tag-commit-sha>
git push origin main

# Argo CD auto-syncs the reverted kustomization.yaml → older image tags applied
```

**For python-ai-service specifically:**

```bash
# Abort a running canary (rolls back to stable revision immediately):
kubectl argo rollouts abort python-ai-service -n searchess

# Undo (sets desired state back to the previous stable image):
kubectl argo rollouts undo python-ai-service -n searchess
```

**`prune: false` reduces rollback risk:**  
StatefulSets, PVCs, and ConfigMaps are not auto-deleted during a rollback even if they
temporarily disappear from the desired state. The operator retains full control over
resource deletion.

**Emergency fallback (Argo CD unavailable):**

```bash
bash deployment/server/deploy-server-registry.sh <previous-sha>
```

---

## 12. Evidence locations

| Evidence folder | Contents |
|---|---|
| `docs/deployment/evidence/pre-main-migration/` | Application state on `performance` branch before merge; pods, PVCs, services, health endpoints |
| `docs/deployment/evidence/main-retarget/` | Argo CD Application describe after `targetRevision` was changed to `main`; health and sync status |
| `docs/deployment/evidence/sha-delivery-cycle-1/` | First full CI sha-* image build cycle; live deployment images, kustomize tag file, pod status |
| `docs/deployment/evidence/sealed-secrets/` | SealedSecret describe, generated Secret, resource sync status, health endpoints |
| `docs/deployment/evidence/rollouts-sha-cycle/` | Argo Rollouts tree (all 5 pods healthy, revision 3 stable), sha-* images per service, final `Synced + Healthy` |
| `docs/deployment/evidence/uni-server-cutover/` | Initial cutover from manual k3d to registry-based deployment |
| `docs/deployment/evidence/uni-server-final/` | Final cluster state: nodes, pods, services, PVCs, Postgres schemas, Redis consumer group |
| `docs/deployment/evidence/uni-server-registry/` | Registry overlay baseline README |
| `docs/deployment/evidence/argocd/` | Argo CD installation and application registration |

**Missing evidence noted:**  
No `evidence/argocd-autosync/` folder exists. The auto-sync enablement (adding
`automated: {prune: false, selfHeal: true}` to `searchess-application.yaml`) is
captured in the `searchess-application.yaml` file itself and implicitly in the
`rollouts-sha-cycle` evidence where the Application shows `Synced + Healthy` after
an auto-triggered sync, but there is no dedicated screenshot or log folder for the
moment auto-sync was first activated.

---

## 13. Completed priority checklist

### Priority 1 — Local and server deployment baseline
- [x] Docker Compose local development environment
- [x] local-k3d overlay — k3d cluster on developer machine
- [x] uni-server-k3d overlay — manual image import on university server
- [x] uni-server-registry overlay — GHCR image pull on university server

### Priority 2 — GitOps and CI/CD pipeline
- [x] Kustomize base + three overlays with full separation
- [x] GitHub Actions: build, push `sha-*` + `main-latest`, update overlay, commit `[skip ci]`
- [x] Argo CD: watches `main`, auto-sync enabled (`selfHeal: true`, `prune: false`)
- [x] Immutable `sha-<commit>` image tags as deployment source of truth
- [x] Infinite-loop prevention: `GITHUB_TOKEN` protection + `paths` allowlist filter + `[skip ci]`
- [x] CI/CD boundary: image builds triggered only by app code / Dockerfile changes; infra and docs commits do not rebuild images

### Priority 3 — Observability and messaging
- [x] Prometheus metrics collection (namespace-scoped)
- [x] Grafana dashboards (domain, HTTP, JVM)
- [x] Redis Streams history delivery with `history-service` consumer group
- [x] Postgres schema isolation (`game` / `history`)
- [x] OpenTelemetry Collector + Tempo — infrastructure deployed; Grafana datasource provisioned
- [x] game-service — OTel Java agent auto-instrumentation (F1 complete; spans visible in Grafana Tempo)
- [x] history-service — OTel Java agent auto-instrumentation (F2 complete; spans visible in Grafana Tempo)
- [ ] ai-service — OTel Java agent instrumentation (F3, pending)
- [ ] python-ai-service — OTel Python SDK instrumentation (F4, pending; separate repo)

### Priority 4 — Security and progressive delivery
- [x] Sealed Secrets — `SealedSecret/searchess-secrets` in uni-server-registry overlay
- [x] Argo Rollouts replica-based canary for `python-ai-service` (5 replicas, 20/50%)
- [x] Manual promotion gate with `kubectl argo rollouts promote`
- [x] Abort and undo procedures documented and tested

### Remaining work

The baseline platform is complete. The only planned remaining items are service-level
OpenTelemetry instrumentation. The Tempo and OTel Collector infrastructure is deployed
and ready to receive spans; nothing will appear in Grafana Explore until services emit
traces.

**F1 and F2 are complete.** game-service and history-service emit spans to Grafana Tempo via Java agent auto-instrumentation.
Remaining instrumentation tasks:

| # | Task | Notes |
|---|---|---|
| F3 | Instrument **ai-service** with OTel Java agent | Same pattern as F1/F2 — `Dockerfile.ai` + `ai-service/configmap.yaml`; agent covers outbound HTTP to python-ai-service; completing this closes the HTTP call chain from game-service |
| F4 | Instrument **python-ai-service** with OTel Python SDK | Separate repo (`arutepsu/searchess-ai-service`); Python SDK; rebuilt via `workflow_dispatch rebuild_python_ai=true` only |
| F5 | Exact L7 traffic splitting (optional) | Current canary is replica-based. Exact per-request splitting requires Istio, Linkerd/SMI, NGINX Ingress, or Envoy routing. Only needed if replica-based control is insufficient. |

See [docs/deployment/opentelemetry-tempo.md](opentelemetry-tempo.md) §6 for the
per-service instrumentation steps.

### Optional future tools — not missing baseline work

The following capabilities are not part of the completed baseline and are not required
unless specific new requirements emerge. They are recorded here for completeness, not
as a deficiency.

- **Helm / Ansible** — Kustomize covers the full deployment lifecycle. Not needed.
- **Keycloak** — OAuth2/OIDC identity provider. Only relevant if user authentication is added.
- **cert-manager** — TLS automation. Only relevant if a public domain is registered.
- **Linkerd / Istio** — Service mesh and exact L7 traffic splitting. Only relevant if F5 is pursued.
- **AnalysisTemplate** — Automated canary health gating via Prometheus metrics. Only relevant if Rollout promotion gates are automated.

---

## 14. Known limitations

| Limitation | Impact | Mitigation / Future path |
|---|---|---|
| No TLS | All traffic is plaintext within the cluster and over the SSH tunnel | cert-manager + a public domain (or internal CA) |
| No public domain | Application is only reachable via SSH tunnel | Register a domain, add Ingress/cert-manager |
| No service mesh | Traffic splitting is pod-count-based, not L7-weighted | Istio, Linkerd, or Envoy Gateway |
| No exact canary traffic splitting | 20% weight ≈ 1 pod, not 20% of requests | Service mesh with traffic routing |
| `prune: false` | Orphaned resources accumulate unless manually deleted | Enable after operators are confident in lifecycle |
| Partial service span emission | game-service (F1) and history-service (F2) emit spans; ai-service and python-ai-service have no spans yet (F3–F4) — HTTP call chain traces are incomplete | Instrument remaining services — see docs/deployment/opentelemetry-tempo.md §6 |
| No automated canary analysis | Canary health is assessed manually | Add AnalysisTemplate + Prometheus metrics gate |
| No production secret rotation | Sealed Secrets key is static; rotation requires re-sealing | KMS-backed key, automated re-sealing pipeline |
| Mongo 4.4 (not 7.0) | Missing features and security fixes in Mongo 5+ | Move to a VM or node with AVX support |
| Python AI: fake/random backend | No real supervised inference if `INFERENCE_BACKEND=fake` | Mount a model artifact volume for supervised mode |

---

## 15. Final conclusion

The Searchess platform now demonstrates a complete, professional cloud-native delivery
pipeline operating end-to-end on a shared university server:

```
Code review → main merge
  → GitHub Actions builds immutable sha-* images
  → Kustomize overlay updated and committed
  → Argo CD auto-syncs desired state to k3s
  → Argo Rollouts drives canary progression for the AI service
  → Operator promotes through staged weight gates
  → Cluster reaches Synced + Healthy
```

The architecture covers the full spectrum from local development (Compose, local-k3d)
through a production-like GitOps pipeline (Argo CD, Sealed Secrets, Argo Rollouts) with
observable infrastructure (Prometheus, Grafana) and a messaging backbone (Redis Streams).

The deliberate deferrals — no service mesh, no TLS, no auto-prune — reflect reasoned
trade-offs for a constrained university environment, not missing knowledge. Each is a
documented next step with a clear upgrade path.

**Final recorded state:**

```
Application:   searchess
Sync status:   Synced
Health:        Healthy
Branch:        main
python-ai:     Rollout — Healthy — 5/5 pods — revision 3 — sha-2e50332 (stable)
```
