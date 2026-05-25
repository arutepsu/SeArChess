#!/usr/bin/env bash
# Bootstrap Argo CD on the searchess-server k3d cluster and register the
# Searchess application for GitOps-managed deployment.
#
# Idempotent: safe to re-run.
#
# Prerequisites:
#   - k3d cluster 'searchess-server' already exists
#     (create it first with: bash deployment/server/deploy-server-registry.sh)
#   - Internet access from the server (Argo CD images + manifest are pulled remotely)
#   - No sudo required
#
# Usage (run from repo root on the server):
#   bash deployment/server/deploy-server-argocd.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

CLUSTER=searchess-server

# ── 1. Confirm cluster exists ─────────────────────────────────────────────────
if ! k3d cluster list | grep -q "^$CLUSTER"; then
  echo "ERROR: k3d cluster '$CLUSTER' not found."
  echo "       Create it first:"
  echo "         bash deployment/server/deploy-server-registry.sh"
  exit 1
fi

echo "==> Merging kubeconfig for '$CLUSTER'"
k3d kubeconfig merge "$CLUSTER" --kubeconfig-merge-default

# ── 2. Install Argo CD ────────────────────────────────────────────────────────
bash deployment/argocd/install-argocd.sh

# ── 3. Apply AppProject ───────────────────────────────────────────────────────
echo "==> Applying AppProject: searchess"
kubectl apply -f deployment/argocd/searchess-project.yaml

# ── 4. Apply Application ──────────────────────────────────────────────────────
echo "==> Applying Application: searchess"
kubectl apply -f deployment/argocd/searchess-application.yaml

# ── 5. Print status commands ──────────────────────────────────────────────────
echo ""
echo "==> Argo CD setup complete."
echo ""
echo "── Argo CD pods ──────────────────────────────────────────────────────────"
kubectl -n argocd get pods
echo ""
echo "── Application status ────────────────────────────────────────────────────"
kubectl -n argocd get applications
echo ""
echo "─── Next steps ────────────────────────────────────────────────────────────"
echo ""
echo "  Access the UI (run in a separate terminal on the server):"
echo "    bash deployment/argocd/port-forward-argocd.sh"
echo ""
echo "  From Windows, open the SSH tunnel first:"
echo "    ssh -L 8080:localhost:8080 chess@141.37.74.145"
echo "  Then open: https://localhost:8080"
echo ""
echo "  Trigger a manual sync from the UI, or:"
echo "    argocd app sync searchess              # if argocd CLI is installed"
echo "    kubectl -n argocd get application searchess -o yaml | grep -A5 status:"
echo ""
echo "  Verify the searchess namespace:"
echo "    kubectl get pods -n searchess"
echo "    curl http://localhost:10000/health"
