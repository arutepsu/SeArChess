# Searchess — Release, Branching, and GitOps Strategy

**Status:** Draft — pre-migration  
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

## 3. Current state (as of 2026-05-25)

- Argo CD Application `searchess` watches:
  - **repo:** `https://github.com/arutepsu/SeArChess.git`
  - **targetRevision:** `performance`
  - **path:** `deployment/k8s/overlays/uni-server-registry`
- GHCR registry push and pull works end-to-end.
- Manual Argo CD sync succeeds; Application reached `Synced + Healthy`.
- StatefulSet drift from k3s-defaulted fields is fully resolved:
  - Kubernetes-defaulted `spec.*` fields declared explicitly in base manifests.
  - `volumeClaimTemplates` embedded PVC fields (`apiVersion`, `kind`,
    `creationTimestamp`, `volumeMode`) declared explicitly.
  - `ignoreDifferences` rules added for `k3s` manager + `volumeClaimTemplates[].status`.
- Direct external server ports are blocked. SSH tunnel remains the only access method:
  ```sh
  ssh -L 10000:localhost:10000 chess@<university-server>
  ```
- Auto-sync is **disabled**. All syncs are initiated manually.

---

## 4. Target state

### Branch

Argo CD `targetRevision` must be changed from `performance` to `main` once the
migration in section 7 is complete.

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

Operator
  └─ manually syncs via Argo CD UI or CLI:
       argocd app sync searchess
```

No auto-sync. No auto-prune. The operator has a deliberate gate before the university
server is updated.

---

## 6. Continuous deployment flow (later)

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

Update GitHub Actions to:
1. Build and push images tagged `sha-<git-sha>` on every push to `main`.
2. Run a post-build step that updates the `newTag` fields in
   `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` and commits the
   result back to `main`.

Until this step is complete, image tags in the overlay must be updated manually before
each deployment.

### Phase 7 — Keep manual sync

Run at least five deployment cycles (code change → image push → tag update commit →
manual sync → verify healthy) before considering auto-sync. Document each cycle.

### Phase 8 — Optionally enable auto-sync

After Phase 7 is validated, enable `automated.selfHeal: true` in the Application.
Leave `prune: false` until resource lifecycle is well understood.

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

## 9. Recommended next work (in order)

1. **Merge `performance` into `main`** (Phase 2–3 above). This is a prerequisite for
   everything else.

2. **Retarget Argo CD to `main`** (Phase 5). Do this immediately after the merge so
   there is no period where `main` is ahead of what Argo CD watches.

3. **Implement immutable SHA image tag update workflow** (Phase 6). Without this, the
   overlay image tags must be bumped manually for every deployment, which is error-prone
   and does not scale.

4. **Sealed Secrets.** Once the delivery pipeline is stable, replace manually managed
   Kubernetes Secrets with Sealed Secrets so that encrypted secrets can be stored safely
   in Git.

5. **Argo Rollouts for `python-ai-service`.** The AI service is the component most
   likely to benefit from canary or blue/green strategies because it has external
   inference latency and is independently deployable.

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
