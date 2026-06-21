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
#   bash deployment/server/deploy-server-registry.sh
#       No-arg: applies the committed registry overlay exactly as-is.
#       Image tags are whatever sha-* values are committed in
#       deployment/k8s/overlays/uni-server-registry/kustomization.yaml.
#
#   bash deployment/server/deploy-server-registry.sh <sha>
#       Emergency pin/rollback: temporarily overrides all sha-* app images with
#       the given tag, applies the overlay, then restores kustomization.yaml to
#       its exact git HEAD state (trap-guaranteed, even on failure).
#       Use immutable sha-<7-char-git-sha> tags only. Do not pass floating tags.
set -euo pipefail

export PATH="$HOME/bin:$PATH"

CLUSTER=searchess-server
CLUSTER_CONFIG=deployment/k3d/server-cluster.yaml
OVERLAY=deployment/k8s/overlays/uni-server-registry
TAG="${1:-}"

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

# ── 4. Apply overlay ──────────────────────────────────────────────────────────
if [ -z "$TAG" ]; then
  # No-arg: apply exactly what is committed. No kustomize edit, no file mutation.
  echo "==> Applying committed registry overlay (image tags as committed in kustomization.yaml)"
  kubectl apply -k "$OVERLAY"
else
  # Explicit tag: emergency pin/rollback to a specific immutable sha-* tag.
  echo "==> Emergency pin: overriding all sha-* app images to tag: $TAG"

  # Guard: refuse to mutate a file that already has local edits.
  git diff --quiet -- "$OVERLAY/kustomization.yaml" || {
    echo "ERROR: $OVERLAY/kustomization.yaml has local changes; aborting to avoid data loss"
    exit 1
  }

  # Restore exactly to git HEAD on exit — success, failure, or signal.
  # This guarantees kustomization.yaml is never left dirty.
  trap 'echo "==> Restoring kustomization.yaml to git HEAD"; git checkout -- "$OVERLAY/kustomization.yaml"' EXIT

  # Image names must match the 'name:' fields in kustomization.yaml exactly.
  # web-ui intentionally keeps its 'main' tag (imagePullPolicy: Always + deploy-sha
  # annotation triggers Argo CD rollout — not a sha-* service).
  # mongo is infrastructure-pinned to 4.4 and is not app code.
  (
    cd "$OVERLAY"
    kustomize edit set image \
      quay.io/keycloak/keycloak=ghcr.io/arutepsu/searchess-keycloak:"$TAG" \
      searchess/ai-service=ghcr.io/arutepsu/searchess-ai-service:"$TAG" \
      searchess/analytics-service=ghcr.io/arutepsu/searchess-analytics-service:"$TAG" \
      searchess/bot-service=ghcr.io/arutepsu/searchess-bot-service:"$TAG" \
      searchess/game-service=ghcr.io/arutepsu/searchess-game-service:"$TAG" \
      searchess/history-service=ghcr.io/arutepsu/searchess-history-service:"$TAG" \
      searchess/lichess-bridge-service=ghcr.io/arutepsu/searchess-lichess-bridge-service:"$TAG" \
      searchess/python-ai-service=ghcr.io/arutepsu/searchess-python-ai-service:"$TAG" \
      searchess/spark-analytics=ghcr.io/arutepsu/searchess-spark-analytics:"$TAG" \
      searchess/tournament-service=ghcr.io/arutepsu/searchess-tournament-service:"$TAG" \
      searchess/gateway-service=ghcr.io/arutepsu/searchess-gateway-service:"$TAG" \
      searchess/user-service=ghcr.io/arutepsu/searchess-user-service:"$TAG"
    kubectl apply -k .
  )
  # EXIT trap above fires here and restores kustomization.yaml.
fi

echo ""
echo "==> Deployment submitted. Watch pods come up:"
echo "    kubectl get pods -n searchess -w"
echo ""
echo "    JVM services take 60–90 s on first boot."
echo "    Run deployment/server/verify-server-registry.sh when all pods are Running."
