#!/usr/bin/env bash
# Install Argo CD into the searchess-server k3d cluster.
#
# Argo CD runs in namespace 'argocd'. The argocd-server Service is left as
# ClusterIP — access it via kubectl port-forward (see port-forward-argocd.sh).
#
# Prerequisites:
#   - kubectl configured for the searchess-server cluster
#   - Internet access from the server (to pull the install manifest and images)
#   - No sudo required
#
# Usage (run from any directory on the server):
#   bash deployment/argocd/install-argocd.sh
#
# Override the version:
#   ARGOCD_VERSION=v2.13.3 bash deployment/argocd/install-argocd.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

ARGOCD_VERSION="${ARGOCD_VERSION:-v2.13.3}"
MANIFEST_URL="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"

echo "==> Installing Argo CD ${ARGOCD_VERSION}"

# ── 1. Namespace ───────────────────────────────────────────────────────────────
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -

# ── 2. Apply upstream install manifest ────────────────────────────────────────
echo "==> Fetching: $MANIFEST_URL"
kubectl apply -n argocd -f "$MANIFEST_URL"

# ── 3. Wait for core deployments ──────────────────────────────────────────────
echo "==> Waiting for argocd-server (timeout 5 min)..."
kubectl -n argocd rollout status deployment/argocd-server          --timeout=300s

echo "==> Waiting for argocd-repo-server..."
kubectl -n argocd rollout status deployment/argocd-repo-server     --timeout=300s

echo "==> Waiting for argocd-application-controller..."
# v2.12+ runs the application-controller as a StatefulSet; older versions use a Deployment.
if kubectl -n argocd get statefulset argocd-application-controller &>/dev/null; then
  kubectl -n argocd rollout status statefulset/argocd-application-controller --timeout=300s
else
  kubectl -n argocd rollout status deployment/argocd-application-controller  --timeout=300s
fi

# ── 4. Tune reconciliation interval ───────────────────────────────────────────
# GitHub webhooks (/api/webhook) are the instant-detection option, but require
# Argo CD to be publicly reachable — not possible on the university server without
# exposing a port. The next-best option is shorter Git polling.
# Default: 3 minutes. Target: ~60–75 s (60s + up to 15s jitter).
echo "==> Patching argocd-cm: timeout.reconciliation=60s jitter=15s"
kubectl -n argocd patch configmap argocd-cm --type merge -p \
  '{"data":{"timeout.reconciliation":"60s","timeout.reconciliation.jitter":"15s"}}'

# ── 5. Restart application-controller to apply argocd-cm changes ──────────────
echo "==> Restarting argocd-application-controller..."
if kubectl -n argocd get statefulset argocd-application-controller &>/dev/null; then
  kubectl -n argocd rollout restart statefulset/argocd-application-controller
  kubectl -n argocd rollout status  statefulset/argocd-application-controller --timeout=120s
else
  kubectl -n argocd rollout restart deployment/argocd-application-controller
  kubectl -n argocd rollout status  deployment/argocd-application-controller  --timeout=120s
fi

# ── 6. Print next steps ────────────────────────────────────────────────────────
echo ""
echo "==> Argo CD ${ARGOCD_VERSION} is ready."
echo ""
echo "    Retrieve the initial admin password:"
echo "      kubectl -n argocd get secret argocd-initial-admin-secret \\"
echo "        -o jsonpath='{.data.password}' | base64 -d && echo"
echo ""
echo "    Access the UI (run in a separate terminal on the server):"
echo "      bash deployment/argocd/port-forward-argocd.sh"
echo ""
echo "    From Windows, first open an SSH tunnel:"
echo "      ssh -L 8080:localhost:8080 chess@141.37.74.145"
echo "    Then open: https://localhost:8080"
echo ""
echo "    Apply the AppProject and Application:"
echo "      kubectl apply -f deployment/argocd/searchess-project.yaml"
echo "      kubectl apply -f deployment/argocd/searchess-application.yaml"
