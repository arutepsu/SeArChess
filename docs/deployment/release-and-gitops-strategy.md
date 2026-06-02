# Searchess — Release, Branching, and GitOps Strategy

**Status:** Migration complete — auto-sync enabled  
**Date:** 2026-05-25  
**Scope:** University server deployment; local environments as supporting context.

---

## 1. Branch model

### Stable branch

`main` is the long-term stable integration branch. It is the single source of truth
for production-grade deployments. Once the migration in section 7 is complete, every
deployment to the university server must trace back to a commit on `main`.

### Temporary branches

| Branch pattern | Purpose | Lifetime |
|---|---|---|
| `feature/*` | New capabilities; merged to `main` via PR | Until merged |
| `fix/*` | Bug fixes; merged to `main` via PR | Until merged |
| `performance` | **Temporary.** Development and performance testing branch used during the k3s/Argo CD integration phase. | Retire after merge to `main` |

`performance` is **not** a long-term branch. It was used to develop and validate the
initial Argo CD GitOps setup, the Kustomize overlays, the GHCR registry pipeline, and
the StatefulSet drift fixes. After review and merge to `main`, it should be treated as
read-only historical context. New development work must not restart on `performance`.

### Release tags

Validated deployment milestones are marked with annotated Git tags on `main`:

```
v<major>.<minor>.<patch>[-<label>]
```

Examples:
- `v0.1.0-gitops` — first GitOps-managed milestone (marks the state after merging `performance`)
- `v0.2.0` — next stable release after SHA-tag image pipeline is in place

Tags are the canonical rollback targets. Reverting a deployment means syncing Argo CD
to a previous tag or the commit a tag points to.

---

## 2. Environment model

| Environment | How managed | Overlay | Notes |
|---|---|---|---|
| Local Docker Compose | `docker compose up` | n/a | Developer iteration; not Kubernetes |
| `local-k3d` | Manual `kubectl apply` or `argocd` CLI | `deployment/k8s/overlays/local-k3d` | Local k3d cluster; no registry |
| `uni-server-k3d` | Manual `kubectl apply` fallback | `deployment/k8s/overlays/uni-server-k3d` | Emergency fallback on the university server if Argo CD is unavailable |
| `uni-server-registry` | **GitOps via Argo CD** | `deployment/k8s/overlays/uni-server-registry` | Primary university server deployment; Argo CD watches this overlay |

Future environments (`staging`, `production`) would each get their own Kustomize overlay
and a separate Argo CD Application object. They are out of scope until the core delivery
pipeline is proven on the university server.

---

## 3. Current state (post-migration, as of 2026-05-25)

> **Historical note:** The initial deployment targeted the `performance` branch and used
> manual Argo CD sync. The migration described in section 7 is complete. The state below
> reflects the live platform after all phases were applied.

- Argo CD Application `searchess` watches:
  - **repo:** `https://github.com/arutepsu/SeArChess.git`
  - **targetRevision:** `main`
  - **path:** `deployment/k8s/overlays/uni-server-registry`
- GHCR registry push and pull works end-to-end with immutable `sha-<7-char-commit>` tags.
- Argo CD auto-sync is **enabled** (`automated: {selfHeal: true, prune: false}`).
  Applications are synced automatically; `prune` remains false (no resources are auto-deleted).
- `python-ai-service` Rollout promotion remains **manual** — Argo CD syncs the Rollout
  object, but the operator must promote through canary stages.
- CI image builds run **only** when app code, build files, or Dockerfiles change.
  Docs and infrastructure commits do not trigger image rebuilds.
- StatefulSet drift from k3s-defaulted fields is fully resolved:
  - Kubernetes-defaulted `spec.*` fields declared explicitly in base manifests.
  - `volumeClaimTemplates` embedded PVC fields (`apiVersion`, `kind`,
    `creationTimestamp`, `volumeMode`) declared explicitly.
  - `ignoreDifferences` rules added for `k3s` manager + `volumeClaimTemplates[].status`.
- Direct external server ports are blocked. SSH tunnel remains the only access method:
  ```sh
  ssh -L 10000:localhost:10000 chess@<university-server>
  ```

---

## 4. Target state — achieved

> All items in this section are now in place. The section is kept for reference.

### Branch

Argo CD `targetRevision` was changed from `performance` to `main` as part of Phase 5.

### Image tags

Deployments must use **immutable git SHA tags**, not mutable convenience tags.

| Tag type | Format | Role |
|---|---|---|
| Immutable (deployment source of truth) | `sha-<7-char-git-sha>` | What Argo CD tracks; what is written into overlay `kustomization.yaml` |
| Convenience (informational only) | `main-latest`, `performance-latest` | May be pushed by CI but must never be the tag referenced in the overlay |

Deploying from `main-latest` or `performance-latest` means the deployed state is
undefined after the next push. SHA tags make the deployed state reproducible and
auditable.

### Sync policy

Manual sync remains the default until the SHA-tag update workflow is validated over
several cycles. Auto-sync is described as a later phase in section 6.

---

## 5. Continuous delivery flow (target)

Continuous delivery means every merge to `main` produces a deployable artifact and
updates the GitOps repo, but an operator manually triggers the actual deployment.

```
Developer
  │
  ├─ opens PR: feature/* → main
  │
CI (GitHub Actions) on PR
  ├─ runs tests (sbt test, Python tests)
  ├─ builds Docker images (dry-run, no push)
  └─ validates: kubectl kustomize deployment/k8s/overlays/uni-server-registry

Merge to main
  │
CI (GitHub Actions) on push to main
  ├─ builds Docker images for all services
  ├─ pushes to GHCR with tag: sha-<git-sha>
  ├─ pushes convenience tag: main-latest (informational)
  ├─ updates image tags in deployment/k8s/overlays/uni-server-registry/kustomization.yaml
  │    images:
  │      - name: ghcr.io/arutepsu/searchess/game-service
  │        newTag: sha-<git-sha>
  │      ... (repeat for each service)
  └─ commits + pushes the tag update commit to main

Argo CD
  └─ detects OutOfSync (kustomization.yaml changed)
  └─ auto-syncs (selfHeal: true, prune: false)
  └─ python-ai-service Rollout enters canary progression (paused at 20%)
```

Auto-sync is **enabled** (`selfHeal: true`). Resources removed from Git are **not**
auto-deleted (`prune: false`). The operator's manual gate is the Rollout promotion
for `python-ai-service`, not the Argo CD sync:

```sh
kubectl argo rollouts promote python-ai-service -n searchess
```

---

## 6. Continuous deployment flow (active)

Continuous deployment removes the manual sync gate. It is appropriate after:

- The SHA-tag update workflow has run through at least five successful release cycles.
- Rollback via tag revert has been tested at least once.
- The team is comfortable with the blast radius of an automated sync.

Changes from continuous delivery:

```yaml
# deployment/argocd/searchess-application.yaml
syncPolicy:
  automated:
    prune: false      # do not delete resources not in Git (keep false initially)
    selfHeal: true    # re-sync if the live state drifts from Git
```

**Rollback procedure (both CD and CDP):**

Option A — revert the image-tag commit:
```sh
git revert <sha-tag-update-commit>
git push origin main
# Argo CD detects the revert; sync deploys the previous image tags
```

Option B — sync to a release tag:
```sh
# Update targetRevision in searchess-application.yaml to a previous release tag
# e.g., v0.1.0-gitops, then apply
kubectl apply -f deployment/argocd/searchess-application.yaml
argocd app sync searchess
```

---

## 7. Migration plan: performance → main

The steps below move the deployment source of truth from `performance` to `main`
without service disruption.

### Phase 1 — Freeze performance and save evidence

- Do not merge new application-code changes to `performance`.
- Tag the current tip of `performance` as a reference point:
  ```sh
  git tag pre-migration-performance-tip
  git push origin pre-migration-performance-tip
  ```
- Save Argo CD sync evidence (screenshots or `kubectl get application` output) in
  `docs/deployment/evidence/`.

### Phase 2 — Open PR: performance → main

- Create a pull request from `performance` to `main`.
- The PR description should summarise:
  - What was built on `performance` (Kustomize overlays, Argo CD setup, StatefulSet
    drift fixes, GHCR registry pipeline).
  - What tests or manual validation confirmed the deployment was `Synced + Healthy`.
  - Which files changed relative to the last `main` merge base.

### Phase 3 — Review and merge

- Review the diff for:
  - Unintended application-code changes mixed in from performance work.
  - Any `performance`-specific image tags hard-coded in overlays that should become
    `main`-derived tags.
  - Any temporary workarounds that should not carry forward.
- Merge using a merge commit (preserves the full history of the performance branch).
- Do not squash; the individual commits document the GitOps debugging history.

### Phase 4 — Tag the working milestone

```sh
git tag -a v0.1.0-gitops -m "First GitOps-managed university server deployment"
git push origin v0.1.0-gitops
```

This tag marks the state of `main` immediately after the merge. It is the rollback
anchor for Phase 5 and 6.

### Phase 5 — Retarget Argo CD to main

Edit `deployment/argocd/searchess-application.yaml`:

```yaml
spec:
  source:
    targetRevision: main   # was: performance
```

Apply on the university server:
```sh
cd ~/searchess-k3d-deploy
git pull
kubectl apply -f deployment/argocd/searchess-application.yaml
kubectl annotate application searchess -n argocd \
  argocd.argoproj.io/refresh=hard --overwrite
```

Verify the Application remains `Synced + Healthy` and the live image tags match `main`.

### Phase 6 — Implement SHA image tag update workflow

**Status: complete** — implemented on branch `feature/sha-image-tags`.

`.github/workflows/build-images.yml` was updated to:

1. Trigger on `push` to `main` (not `performance`), using a `paths` allowlist
   (app code, build files, Dockerfiles only) so infrastructure and doc commits do not
   trigger image rebuilds. `kustomization.yaml` is not in the allowlist, preventing
   the overlay update commit from re-triggering the workflow.
2. Build and push images tagged `sha-<7-char-git-sha>` (immutable, deployment tag)
   and `main-latest` (floating convenience tag) for all four services.
3. Run an `update-overlay` job that installs kustomize, calls
   `kustomize edit set image` to update the four service `newTag` fields to
   `sha-<7-char-git-sha>`, and commits only `kustomization.yaml` back to `main`
   with message `Deploy images sha-<SHORT_SHA> [skip ci]`.
4. Three independent layers prevent the overlay commit from triggering an infinite
   build loop: `GITHUB_TOKEN` push protection (GitHub built-in), the `paths` allowlist
   filter (kustomization.yaml is not a listed app-code path), and `[skip ci]` in the
   commit message.

Key files changed in Phase 6:
- `.github/workflows/build-images.yml`
- `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` (comment only;
  CI manages the `newTag` values)
- `docs/deployment/registry-deployment.md`
- `deployment/argocd/README.md`

### Phase 7 — Validate delivery cycles — complete

Five or more delivery cycles (code change → image push → tag update commit → auto-sync
→ verify healthy) were run successfully. Manual Rollout promotion was exercised each
cycle. No issues were found that warranted blocking Phase 8.

### Phase 8 — Enable auto-sync — complete

`automated: {selfHeal: true, prune: false}` is active in
`deployment/argocd/searchess-application.yaml`. Resources removed from Git are **not**
auto-deleted (`prune: false`). `python-ai-service` Rollout promotion remains manual.

---

## 8. Safety rules

These rules hold until explicitly superseded by a documented decision.

| Rule | Rationale |
|---|---|
| No direct `kubectl apply` for application resources in `searchess` namespace except emergency | GitOps is the source of truth; manual applies create untracked drift |
| The manual registry deploy script (`scripts/registry-deploy.sh` or equivalent) remains a documented fallback | Argo CD availability is not guaranteed on the university server |
| No auto-prune until confidence is high | Kubernetes may delete live resources if the overlay temporarily omits them (e.g., during a Kustomize refactor) |
| No plaintext Secrets in Git long term | Sealed Secrets (or equivalent) is the next secrets management step |
| No Keycloak, cert-manager, or Linkerd until core delivery pipeline is stable | These add operational complexity before the baseline is solid |
| `kubectl-set` is not added to `ignoreDifferences` | Manual image overrides via `kubectl set image` must remain visible as drift, not silently hidden |
| `performance` must not become a permanent deployment branch | It was a development branch; anchoring deployments to it permanently would mean bypassing PR review for application changes |

---

## 9. Completed work and remaining roadmap

All migration phases (1–8) are complete. The following items were completed:

- ~~Merge `performance` into `main`~~ — done (Phase 2–3)
- ~~Retarget Argo CD to `main`~~ — done (Phase 5)
- ~~Immutable SHA image tag CI workflow~~ — done (Phase 6)
- ~~Sealed Secrets~~ — done; `SealedSecret/searchess-secrets` active in `uni-server-registry`
- ~~Argo Rollouts canary for `python-ai-service`~~ — done; 5 replicas, 20/50% stages
- ~~Auto-sync enabled~~ — done (Phase 8)

**Remaining planned work:**

1. **Instrument services with OpenTelemetry.** The Tempo and OTel Collector
   infrastructure is deployed. Service spans require SDK instrumentation:
   game-service first (Java agent), then history-service and ai-service, then
   python-ai-service last (separate repo, ML dependencies). See
   `docs/deployment/opentelemetry-tempo.md` §6.

2. **Exact L7 traffic splitting (optional).** The current canary is replica-based.
   Exact per-request splitting would require Istio, Linkerd/SMI, NGINX Ingress, or
   Envoy traffic routing. Only needed if replica-based canary control is insufficient.

---

## Appendix A — Files that change during migration

| File | Change |
|---|---|
| `deployment/argocd/searchess-application.yaml` | `targetRevision: performance` → `targetRevision: main` (Phase 5) |
| `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` | Image `newTag` values updated from build tags to `sha-<git-sha>` (Phase 6) |
| `.github/workflows/*.yml` | Add SHA-tag build and overlay update steps (Phase 6) |

No application code, no domain logic, no Scala modules, no Python services are changed
by this migration.

---

## Appendix B — Access reference

The university server is not directly reachable on any application port. The SSH tunnel
must be established before testing:

```sh
ssh -L 10000:localhost:10000 chess@<university-server>
# then: curl http://localhost:10000/health
```

Argo CD UI:
```sh
ssh -L 8080:localhost:8080 chess@<university-server>
# then: open http://localhost:8080
```
