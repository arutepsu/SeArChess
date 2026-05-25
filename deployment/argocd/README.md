# Argo CD — Searchess GitOps

Argo CD runs inside the `searchess-server` k3d cluster in the `argocd` namespace.
It watches the `uni-server-registry` Kustomize overlay and syncs Searchess resources
into the `searchess` namespace on demand (manual sync — no auto-sync yet).

---

## Files

| File | Purpose |
|---|---|
| `install-argocd.sh` | Install Argo CD into the `argocd` namespace |
| `port-forward-argocd.sh` | Forward the Argo CD server to `localhost:8080` for UI access |
| `searchess-project.yaml` | AppProject restricting the repo, cluster, and namespace scope |
| `searchess-application.yaml` | Application pointing at `deployment/k8s/overlays/uni-server-registry` on the `performance` branch |

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

## Sync

Sync is **manual** in this initial setup. Trigger from the UI or argocd CLI:

```bash
# CLI (if installed):
argocd app sync searchess

# Or trigger from the Argo CD UI → Applications → searchess → Sync
```

---

## Design notes

- Argo CD is **not** exposed via LoadBalancer — access is through `kubectl port-forward`
  and optionally an SSH tunnel from Windows.
- Secrets remain plain Kubernetes Secret patches in the overlay until Sealed Secrets
  is introduced.
- Auto-sync and prune are both disabled. Enable them explicitly once the deployment
  is stable and the team is comfortable with Argo CD managing lifecycle.
- The `uni-server-k3d` manual-import workflow and `deploy-server-registry.sh` remain
  available as fallbacks.

See `docs/deployment/registry-deployment.md` for the full deployment context.
