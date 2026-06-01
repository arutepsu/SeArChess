# Searchess — Final Platform Summary

**Last updated:** 2026-06-01 — added Keycloak K8s auth layer (branch: feat/keycloak-k8s-auth)
**Original baseline date:** 2026-05-25  
**Branch:** main → feat/keycloak-k8s-auth  
**Argo CD status:** Synced + Healthy (existing services); Keycloak requires manual setup steps before first sync  

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
- No automated canary analysis (AnalysisTemplate not wired).
- Mongo is pinned to 4.4 due to AVX CPU limitations on the university VM.
- Keycloak: HTTP-only (no TLS); token issuer hardcoded to `127.0.0.1:8080` (SSH tunnel / port-forward only); web-ui image not yet built by CI.

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
  ├─ Argo Rollout:  python-ai-service (canary, 1 replica — 4 GB VM capacity)
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
| python-ai-service | **Rollout** | `ghcr.io/arutepsu/searchess-python-ai-service:sha-*` | 8765 | Python inference backend — canary-controlled; 1 replica on university server |
| **web-ui** ¹ | Deployment | `ghcr.io/arutepsu/searchess-web-ui:sha-*` | 80 (internal) | React SPA — served at `/` through Envoy |
| **keycloak** ¹ | Deployment | `quay.io/keycloak/keycloak:26.6.2` | 8080 | OIDC identity provider — JWT issuer for API auth |
| postgres | StatefulSet | `postgres:16` | 5432 | Persistent storage — schemas: `game`, `history` |
| mongo | StatefulSet | `mongo:4.4` | 27017 | Document store (pinned 4.4 — no AVX on university VM) |
| redis | StatefulSet | `redis:7.4-alpine` | 6379 | Redis Streams — history archive delivery channel |
| prometheus | Deployment | `prom/prometheus:v2.55.1` | 9090 | Metrics scrape and storage |
| grafana | Deployment | `grafana/grafana:11.4.0` | 3000 (→ 33001 ext) | Dashboard UI — Prometheus + Tempo datasources |
| tempo | Deployment | `grafana/tempo:2.6.1` | 3200 (query), 4317 (OTLP recv) | Trace storage — receives from otel-collector, queried by Grafana |
| otel-collector | Deployment | `otel/opentelemetry-collector-contrib:0.114.0` | 4317 (gRPC), 4318 (HTTP) | Trace ingestion — receives OTLP from services, forwards to Tempo |

¹ **Branch `feat/keycloak-k8s-auth` — not yet merged or validated on university server.** Keycloak requires sealed credentials and Postgres init before first deploy; web-ui image must be built manually before CI integration is wired. Envoy on main does not carry JWT auth; Envoy on the branch does.

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
  │  (university server: 1 replica total — canary gate is preserved;
  │   traffic split degenerates to 1 canary / 0 stable at all weight steps)
  │  Step 1: setWeight 20  → 1 canary pod / 0 stable pods
  │  Step 2: pause {}      → PAUSED — operator must act
  │
  ▼ operator: kubectl argo rollouts promote python-ai-service -n searchess
  │
  │  Step 3: setWeight 50  → 1 canary pod / 0 stable pods
  │  Step 4: pause {}      → PAUSED — operator must act
  │
  ▼ operator: kubectl argo rollouts promote python-ai-service -n searchess
  │
  │  Full: 1 pod on new image, stable ReplicaSet scaled to zero
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
replicas: 1 on university server (4 GB RAM; a higher count would exhaust available memory)
maxSurge: 1   maxUnavailable: 0

With 1 total replica, canary weight steps degenerate — there is only ever 1 pod running.
The pause gates are preserved and remain meaningful as a human checkpoint before
committing to the new image. Traffic splitting between canary and stable is not possible.

setWeight 20  →  1 canary pod,  0 stable pods
pause {}      →  INDEFINITE — operator must promote or abort
setWeight 50  →  1 canary pod,  0 stable pods
pause {}      →  INDEFINITE — operator must promote or abort
100%          →  1 pod on new image, stable ReplicaSet scaled to zero
```

**This is replica-based canary, not L7 traffic splitting.**  
`Service/python-ai-service` selects all pods with `app=python-ai-service` regardless
of whether they are canary or stable. kube-proxy distributes connections proportional
to pod count. With 1 replica, all traffic always goes to whichever pod is running.
There is no per-request routing at any replica count.

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
| uni-server-registry overlay | `SealedSecret/keycloak-secrets` | **Placeholder** — operator must run `scripts/seal-keycloak-secrets.sh` |
| Sealed Secrets controller | Decrypts SealedSecrets → creates `Secret/searchess-secrets`, `Secret/keycloak-secrets` | Running in `kube-system` |
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
| Distributed tracing — service spans | OpenTelemetry SDK in services | **Complete (F1–F4)** — all four services emit spans; full call chain visible in Grafana Tempo |
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
| Application HTTP + Web UI | SSH tunnel: `ssh -L 10000:localhost:10000 chess@<server>` then `http://127.0.0.1:10000` |
| Keycloak (OIDC, admin) | SSH tunnel: `-L 8080:localhost:8080` or `kubectl port-forward -n searchess svc/keycloak 8080:8080` |
| Grafana UI | SSH tunnel: `ssh -L 33001:localhost:33001 chess@<server>` then `http://localhost:33001` |
| Web UI (local dev against deployed backend) | `npm run dev:deployed` from `apps/web-ui/` (requires both tunnels active) |

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
| `docs/deployment/evidence/rollouts-sha-cycle/` | Argo Rollouts tree captured at 5 pods (prior to VM-capacity reduction to 1 replica), sha-* images per service, final `Synced + Healthy` |
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
- [x] ai-service — OTel Java agent auto-instrumentation (F3 complete; HTTP call chain game-service → ai-service linked in Tempo)
- [x] python-ai-service — OTel Python SDK instrumentation (F4 complete; separate repo `arutepsu/searchess-ai-service`)

### Priority 4 — Security and progressive delivery
- [x] Sealed Secrets — `SealedSecret/searchess-secrets` in uni-server-registry overlay
- [x] Argo Rollouts replica-based canary for `python-ai-service` (1 replica on university server — 4 GB VM capacity; pause/promote gate mechanism preserved)
- [x] Manual promotion gate with `kubectl argo rollouts promote`
- [x] Abort and undo procedures documented and tested

### Priority 5 — Authentication (branch: feat/keycloak-k8s-auth)
- [x] Keycloak Kubernetes Deployment + Service (`quay.io/keycloak/keycloak:26.6.2`, replicas: 1)
- [x] Keycloak realm import via ConfigMap (`searchess` realm, PKCE S256, audience mapper)
- [x] Keycloak Postgres backend — separate `keycloak` database, `keycloak` user
- [x] Postgres init Job — idempotent DB/user creation before Keycloak starts
- [x] `SealedSecret/keycloak-secrets` pattern — seal script provided, placeholder committed
- [x] Envoy JWT auth on `/api/*` — JWKS from internal `keycloak:8080`, issuer `127.0.0.1:8080`
- [x] Web UI Kubernetes Deployment + Service (nginx SPA, served at `/` through Envoy)
- [x] Web UI auth config environment-driven (`VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, etc.)
- [x] Web UI Dockerfile (multi-stage, build args for VITE_* vars, nginx runtime)
- [ ] Web UI CI integration (CI build of `searchess-web-ui` image) — manual build for first deploy

### Remaining work

**F1–F4 OpenTelemetry instrumentation is complete.** All four services emit spans to Grafana Tempo:

- game-service — Java agent auto-instrumentation (F1)
- history-service — Java agent auto-instrumentation (F2)
- ai-service — Java agent auto-instrumentation (F3)
- python-ai-service — OTel Python SDK (F4; separate repo `arutepsu/searchess-ai-service`)

The full game-service → ai-service → python-ai-service HTTP call chain is visible as a linked trace in Grafana Tempo.

**Open items from the Keycloak auth branch (`feat/keycloak-k8s-auth`):**

| Item | Status |
|---|---|
| Seal Keycloak credentials with `kubeseal` | Operator action required before first deploy — run `scripts/seal-keycloak-secrets.sh` |
| Run Postgres init Job for Keycloak database | One-time manual step before Keycloak starts |
| Build and push web-ui Docker image manually | Required before first deploy; CI integration is a follow-up task |
| Wire `apps/web-ui/**` into build-images.yml | CI follow-up — automates web-ui image rebuild on code changes |
| Validate Keycloak on university server | Not yet done — see `docs/deployment/demo-checklist.md` |

**Optional future work (no current timeline):**

| Item | Notes |
|---|---|
| F5 — Exact L7 traffic splitting | Replica-based canary is sufficient; exact per-request splitting requires Istio, Linkerd/SMI, or Envoy routing |
| cert-manager + TLS | Only relevant once a public domain is registered |
| AnalysisTemplate automated canary gating | Only relevant if manual promotion gates need automation |

### Optional future tools — not missing baseline work

The following capabilities are not part of the completed baseline and are not required
unless specific new requirements emerge. They are recorded here for completeness, not
as a deficiency.

- **Helm / Ansible** — Kustomize covers the full deployment lifecycle. Not needed.
- **cert-manager** — TLS automation. Only relevant if a public domain is registered. Keycloak on `feat/keycloak-k8s-auth` is HTTP-only pending TLS.
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
| No automated canary analysis | Canary health is assessed manually | Add AnalysisTemplate + Prometheus metrics gate |
| python-ai-service: 1 replica on university server | No redundancy; restart causes AI move downtime | Increase replicas when VM capacity allows (or migrate to a larger node) |
| No production secret rotation | Sealed Secrets key is static; rotation requires re-sealing | KMS-backed key, automated re-sealing pipeline |
| Mongo 4.4 (not 7.0) | Missing features and security fixes in Mongo 5+ | Move to a VM or node with AVX support |
| Python AI: fake/random backend | No real supervised inference if `INFERENCE_BACKEND=fake` | Mount a model artifact volume for supervised mode |
| Keycloak: HTTP-only, issuer `127.0.0.1:8080` (branch) | Not accessible without SSH tunnel / port-forward; tokens not valid for a public URL | cert-manager + public domain; update `KC_HOSTNAME` and realm redirect URIs |
| Keycloak: not yet validated on university server (branch) | `keycloak-sealed-secret.yaml` has placeholder values; Keycloak will not start until operator runs seal script | Run `scripts/seal-keycloak-secrets.sh`, commit result, run Postgres init Job |

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

**Final recorded state (main baseline — before Keycloak branch):**

```
Application:   searchess
Sync status:   Synced
Health:        Healthy
Branch:        main
python-ai:     Rollout — Healthy — 1/1 pods — sha-2247329 (stable)
```

*Keycloak (`feat/keycloak-k8s-auth`) is branch work. It is not yet merged, not yet validated on the university server, and not reflected in the Synced/Healthy status above.*
