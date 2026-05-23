#!/usr/bin/env bash
# Forward the Argo CD server to localhost:8080 so the UI is reachable.
#
# Run this in a dedicated terminal on the university server.
# Keep it open while using the Argo CD UI.
#
# Usage:
#   bash deployment/argocd/port-forward-argocd.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

echo "==> Port-forwarding Argo CD server → localhost:8080"
echo ""
echo "    UI:  https://localhost:8080"
echo "    (Accept the self-signed TLS certificate warning in your browser.)"
echo ""
echo "    From Windows, open an SSH tunnel in a separate terminal first:"
echo "      ssh -L 8080:localhost:8080 chess@141.37.74.145"
echo "    Then navigate to https://localhost:8080 in your browser."
echo ""
echo "    Login: admin"
echo "    Password:"
echo "      kubectl -n argocd get secret argocd-initial-admin-secret \\"
echo "        -o jsonpath='{.data.password}' | base64 -d && echo"
echo ""
echo "    Press Ctrl-C to stop."
echo ""

kubectl -n argocd port-forward svc/argocd-server 8080:443
