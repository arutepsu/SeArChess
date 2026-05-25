# Argo CD — Searchess GitOps

Argo CD runs inside the `searchess-server` k3d cluster in the `argocd` namespace.
It watches the `uni-server-registry` Kustomize overlay and auto-syncs Searchess
resources into the `searchess` namespace (`selfHeal: true`, `prune: false`).

---

## Files

| File | Purpose |
|---|---|
| `install-argocd.sh` | Install Argo CD, patch reconciliation interval, register app |
| `port-forward-argocd.sh` | Forward the Argo CD server to `localhost:8080` for UI access |
| `searchess-project.yaml` | AppProject restricting the repo, cluster, and namespace scope |
| `searchess-application.yaml` | Application pointing at `deployment/k8s/overlays/uni-server-registry` on the `main` branch |

---

## Quick start

Run from the repo root on the server (cluster must already exist):

```bash
# 1. Install Argo CD + register app in one step:
bash deployment/server/deploy-server-argocd.sh

# 2. In a separate terminal — expose the UI:
bash deployment/argocd/port-forward-argocd.sh

# From Windows, first open an SSH tunnel:
#   ssh -L 8080:localhost:8080 chess@141.37.74.145

# 3. Open in browser: https://localhost:8080
#    Username: admin
#    Password (retrieve from cluster):
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d && echo
```

---

## Auto-sync policy

| Setting | Value |
|---|---|
| `automated.selfHeal` | `true` — Argo CD re-applies Git state if live state drifts |
| `automated.prune` | `false` — resources removed from Git are NOT auto-deleted |
| `syncOptions` | `CreateNamespace=true`, `ServerSideApply=true` |
| `targetRevision` | `main` |

Argo CD detects `OutOfSync` when `kustomization.yaml` changes (CI commits a new
`sha-*` image tag) and applies the change automatically. No operator action is required
for Scala services. The `python-ai-service` Rollout promotion remains manual (see below).

To force an immediate sync without waiting for polling:

```bash
argocd app sync searchess
# or: Argo CD UI → Applications → searchess → Sync
```

---

## Reconciliation interval

Argo CD polls Git to detect changes. The default interval is 3 minutes. The install
script patches `argocd-cm` to reduce this to approximately **60–75 seconds**:

```
timeout.reconciliation:       60s
timeout.reconciliation.jitter: 15s
```

This setting is applied by `install-argocd.sh` step 4 via:

```bash
kubectl -n argocd patch configmap argocd-cm --type merge -p \
  '{"data":{"timeout.reconciliation":"60s","timeout.reconciliation.jitter":"15s"}}'
```

The application controller is restarted immediately after to pick up the change.

### Why not GitHub webhooks?

GitHub webhooks to `/api/webhook` are the true instant-detection option — Argo CD
processes a push event within seconds of the CI commit landing. However, webhooks
require Argo CD to be reachable at a public URL. The university server does not expose
Argo CD via LoadBalancer or public ingress; access is through SSH tunnel /
`kubectl port-forward` only.

Shorter polling is the correct approach for this setup. The 60s target means CI image
commits are detected and applied within approximately 1–2 minutes of the push, which
is acceptable for a university deployment.

If a public URL ever becomes available for this server, the webhook approach is:

```bash
# On GitHub: Settings → Webhooks → Add webhook
#   Payload URL: https://<public-argo-cd-url>/api/webhook
#   Content type: application/json
#   Events: Just the push event

# In argocd-cm, remove or zero-out the timeout:
kubectl -n argocd patch configmap argocd-cm --type merge -p \
  '{"data":{"timeout.reconciliation":"0s"}}'
```

### To update the interval on an existing installation

```bash
kubectl -n argocd patch configmap argocd-cm --type merge -p \
  '{"data":{"timeout.reconciliation":"60s","timeout.reconciliation.jitter":"15s"}}'

# Restart the application controller to apply:
if kubectl -n argocd get statefulset argocd-application-controller &>/dev/null; then
  kubectl -n argocd rollout restart statefulset/argocd-application-controller
else
  kubectl -n argocd rollout restart deployment/argocd-application-controller
fi
```

---

## Argo Rollouts — python-ai-service

`python-ai-service` is managed by an Argo Rollouts canary `Rollout` (not a
`Deployment`) in `uni-server-registry`. It runs **5 replicas** with replica-based
weight approximation — no service mesh required.

After Argo CD syncs a new image tag, the Rollout controller drives:

```
setWeight 20 → 1 canary / 4 stable pods  (~20% traffic via kube-proxy)
pause {}     → wait for operator
setWeight 50 → 3 canary / 3 stable pods  (~50% traffic)
pause {}     → wait for operator
100%         → all 5 pods on the new image
```

Argo CD health shows `Progressing` during an active rollout. This is expected.
Traffic split is proportional to pod counts, not exact L7 percentages.

**The Rollout only enters canary progression when the `python-ai-service` image tag
changes.** Because the python-ai-service code lives in a separate repo, its image is
never rebuilt on a normal push to this repo — only via `workflow_dispatch` with
`rebuild_python_ai=true`. Scala service changes do not trigger the Rollout.

```bash
# Watch rollout progress:
kubectl argo rollouts get rollout python-ai-service -n searchess --watch

# Advance past the current pause:
kubectl argo rollouts promote python-ai-service -n searchess

# Abort and roll back to stable:
kubectl argo rollouts abort python-ai-service -n searchess
```

The Argo Rollouts controller must be installed in the `argo-rollouts` namespace on the
server before Argo CD syncs the Rollout resource. See
`docs/deployment/registry-deployment.md` for the installation command.

---

## Design notes

- Argo CD is **not** exposed via LoadBalancer or public ingress. Access is through
  `kubectl port-forward` and an SSH tunnel from Windows. This is intentional — the
  university server does not have a public URL for the Argo CD endpoint.
- `SealedSecret/searchess-secrets` is active in `uni-server-registry`. No plaintext
  secrets appear in any committed file under that overlay. Plain `secret-dev.yaml`
  patches apply only to local and uni-server-k3d overlays.
- `prune: false` — resources removed from Git are never auto-deleted. Operators
  review and delete orphaned resources manually.
- `kubectl-set` is NOT in `ignoreDifferences` — manual image overrides via
  `kubectl set image` remain visible as drift, not silently hidden.
- The `uni-server-k3d` manual-import workflow and `deploy-server-registry.sh` remain
  available as fallbacks if Argo CD is unavailable.

See `docs/deployment/registry-deployment.md` for the full deployment context.
