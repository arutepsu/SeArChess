#!/usr/bin/env bash
# Deploy searchess using GHCR registry images on the searchess-server k3d cluster.
#
# Prerequisites:
#   - k3d and kubectl installed in ~/bin or on PATH
#   - Docker running (user must be in the 'docker' group — no sudo needed)
#   - Logged in to GHCR: docker login ghcr.io -u <github-username> -p <PAT>
#   - The k3d cluster must exist; create it once with:
#       bash deployment/server/deploy-server-k3d.sh   (runs apply too, switch to registry after)
#     Or: k3d cluster create --config deployment/k3d/server-cluster.yaml
#
# Usage (run from repo root on the server):
#   bash deployment/server/deploy-server-registry.sh [<image-tag>]
#
# If <image-tag> is omitted, 'performance-latest' is used (floating tag, latest
# successful build on the performance branch).
# To pin to a specific commit:
#   bash deployment/server/deploy-server-registry.sh abc1234
set -euo pipefail

export PATH="$HOME/bin:$PATH"

CLUSTER=searchess-server
CLUSTER_CONFIG=deployment/k3d/server-cluster.yaml
OVERLAY=deployment/k8s/overlays/uni-server-registry
TAG="${1:-performance-latest}"

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

# ── 3. GHCR credentials ───────────────────────────────────────────────────────
# k3d nodes need access to GHCR. Create (or update) an image pull secret.
# Requires GHCR_USER and GHCR_TOKEN to be set in the environment, OR for
# docker login ghcr.io to have been run already on this shell.
if [ -n "${GHCR_USER:-}" ] && [ -n "${GHCR_TOKEN:-}" ]; then
  echo "==> Configuring GHCR image pull credentials"
  kubectl create namespace searchess --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret docker-registry ghcr-credentials \
    --namespace searchess \
    --docker-server=ghcr.io \
    --docker-username="$GHCR_USER" \
    --docker-password="$GHCR_TOKEN" \
    --dry-run=client -o yaml | kubectl apply -f -
else
  echo "==> GHCR_USER/GHCR_TOKEN not set — assuming images are public or pull secret exists"
fi

# ── 4. Pin image tag ──────────────────────────────────────────────────────────
if [ "$TAG" != "performance-latest" ]; then
  echo "==> Pinning images to tag: $TAG"
  # Temporarily patch the overlay's images block for this deploy.
  # Uses kustomize edit so we don't modify kustomization.yaml permanently.
  (
    cd "$OVERLAY"
    kustomize edit set image \
      ghcr.io/arutepsu/searchess-game-service="ghcr.io/arutepsu/searchess-game-service:$TAG" \
      ghcr.io/arutepsu/searchess-history-service="ghcr.io/arutepsu/searchess-history-service:$TAG" \
      ghcr.io/arutepsu/searchess-ai-service="ghcr.io/arutepsu/searchess-ai-service:$TAG" \
      ghcr.io/arutepsu/searchess-python-ai-service="ghcr.io/arutepsu/searchess-python-ai-service:$TAG"
    kubectl apply -k .
    # Restore the floating tag so the file stays clean in git.
    kustomize edit set image \
      ghcr.io/arutepsu/searchess-game-service="ghcr.io/arutepsu/searchess-game-service:performance-latest" \
      ghcr.io/arutepsu/searchess-history-service="ghcr.io/arutepsu/searchess-history-service:performance-latest" \
      ghcr.io/arutepsu/searchess-ai-service="ghcr.io/arutepsu/searchess-ai-service:performance-latest" \
      ghcr.io/arutepsu/searchess-python-ai-service="ghcr.io/arutepsu/searchess-python-ai-service:performance-latest"
  )
else
  echo "==> Applying overlay: $OVERLAY (tag: $TAG)"
  kubectl apply -k "$OVERLAY"
fi

echo ""
echo "==> Deployment submitted. Watch pods come up:"
echo "    kubectl get pods -n searchess -w"
echo ""
echo "    JVM services take 60–90 s on first boot."
echo "    Run deployment/server/verify-server-registry.sh when all pods are Running."
