#!/usr/bin/env bash
# Create the searchess-server k3d cluster (if it does not exist) and apply
# the uni-server-k3d Kustomize overlay.
#
# Prerequisites:
#   - k3d and kubectl installed in ~/bin or on PATH
#   - Docker running (no sudo needed if user is in the 'docker' group)
#   - Application images already imported via deployment/server/import-images.sh
#
# Usage (run from repo root):
#   bash deployment/server/deploy-server-k3d.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

CLUSTER=searchess-server
CLUSTER_CONFIG=deployment/k3d/server-cluster.yaml
OVERLAY=deployment/k8s/overlays/uni-server-k3d

# ── 1. Cluster ────────────────────────────────────────────────────────────────
if k3d cluster list | grep -q "^$CLUSTER"; then
  echo "==> Cluster '$CLUSTER' already exists — skipping creation"
else
  echo "==> Creating k3d cluster '$CLUSTER'"
  k3d cluster create --config "$CLUSTER_CONFIG"
fi

# ── 2. Kubeconfig ─────────────────────────────────────────────────────────────
echo "==> Merging kubeconfig for '$CLUSTER'"
k3d kubeconfig merge "$CLUSTER" --kubeconfig-merge-default

# ── 3. Apply overlay ──────────────────────────────────────────────────────────
echo "==> Applying overlay: $OVERLAY"
kubectl apply -k "$OVERLAY"

echo ""
echo "==> Deployment submitted. Watch pods come up:"
echo "    kubectl get pods -n searchess -w"
echo ""
echo "    JVM services take 60–90 s on first boot."
echo "    Run deployment/server/verify-server-k3d.sh when all pods are Running."
