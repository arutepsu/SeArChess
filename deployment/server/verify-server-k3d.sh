#!/usr/bin/env bash
# Verify the searchess-server k3d deployment is healthy.
#
# Usage (run from repo root on the server):
#   bash deployment/server/verify-server-k3d.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

PASS=0
FAIL=0

check() {
  local label="$1"
  local result="$2"
  local expected="${3:-}"
  if [ -n "$expected" ] && ! echo "$result" | grep -q "$expected"; then
    echo "[FAIL] $label"
    echo "       got: $result"
    FAIL=$((FAIL + 1))
  else
    echo "[OK]   $label"
    PASS=$((PASS + 1))
  fi
}

# ── Pods ──────────────────────────────────────────────────────────────────────
echo "==> Pod status"
kubectl get pods -n searchess -o wide
echo ""

for app in game-service history-service ai-service python-ai-service envoy; do
  STATUS=$(kubectl get pods -n searchess -l "app=$app" \
    -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
  check "pod/$app phase=Running" "$STATUS" "Running"
done

for app in postgres mongo redis; do
  STATUS=$(kubectl get pods -n searchess -l "app=$app" \
    -o jsonpath='{.items[0].status.phase}' 2>/dev/null || echo "NotFound")
  check "pod/$app phase=Running" "$STATUS" "Running"
done

# ── Services ──────────────────────────────────────────────────────────────────
echo ""
echo "==> Services"
kubectl get svc -n searchess
echo ""

# ── Health endpoints ──────────────────────────────────────────────────────────
echo "==> Health checks"

HEALTH=$(curl -fsS http://localhost:10000/health 2>&1 || echo "ERROR")
check "GET http://localhost:10000/health" "$HEALTH" '"status"'

GRAFANA=$(curl -fsS http://localhost:33001/api/health 2>&1 || echo "ERROR")
check "GET http://localhost:33001/api/health" "$GRAFANA" '"database"'

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "==> Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
