# Demo Checklist — SeArChess Kubernetes Auth

**Date:** 2026-06-01
**Branch:** feat/keycloak-k8s-auth

This checklist covers the steps to demonstrate the full Keycloak authentication flow
on the university Kubernetes deployment.

---

## Pre-flight: first-time Keycloak setup

These steps are required once before the first demo. They are idempotent and safe to re-run.

### 1. Seal Keycloak credentials

```bash
# From repo root, kubectl context = university cluster
bash scripts/seal-keycloak-secrets.sh
# Prompts for:
#   - Bootstrap admin username  (do NOT use "admin" — choose a real value)
#   - Bootstrap admin password  (strong password)
#   - Keycloak DB password      (strong password)

git add deployment/k8s/overlays/uni-server-registry/keycloak-sealed-secret.yaml
git commit -m "chore: seal keycloak credentials for university server"
git push origin main
```

Wait for Argo CD to sync the `SealedSecret/keycloak-secrets`.

### 2. Init Postgres for Keycloak

```bash
kubectl apply -f deployment/k8s/base/postgres/job-keycloak-db-init.yaml
kubectl wait --for=condition=complete -n searchess job/postgres-init-keycloak --timeout=120s
kubectl logs -n searchess job/postgres-init-keycloak
# Expected last line: "Postgres init for Keycloak complete."
```

### 3. Build and push the web-ui image

The web-ui Docker image is not yet built by CI (CI integration is a follow-up). Build manually:

```bash
# From apps/web-ui/
docker build \
  --build-arg VITE_KEYCLOAK_URL=http://127.0.0.1:8080 \
  --build-arg VITE_KEYCLOAK_REALM=searchess \
  --build-arg VITE_KEYCLOAK_CLIENT_ID=searchess-web \
  --build-arg VITE_API_BASE_URL=http://127.0.0.1:10000 \
  --build-arg VITE_WS_URL=ws://127.0.0.1:10000/ws \
  -t ghcr.io/arutepsu/searchess-web-ui:sha-<commit> .

docker push ghcr.io/arutepsu/searchess-web-ui:sha-<commit>
```

Then update `newTag` in `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` for `searchess/web-ui`.

---

## Demo day checklist

### A. Cluster health

```bash
kubectl get applications -n argocd
# Expected: searchess  Synced  Healthy

kubectl get pods -n searchess
# Expected: all pods Running or Completed
```

### B. Keycloak health

```bash
kubectl get pods -n searchess -l app=keycloak
# Expected: keycloak-<hash>  1/1  Running

kubectl port-forward -n searchess svc/keycloak 8080:8080 &
curl http://127.0.0.1:8080/health/ready
# Expected: {"status":"UP"}

curl http://127.0.0.1:8080/realms/searchess/.well-known/openid-configuration
# Expected: JSON with issuer = http://127.0.0.1:8080/realms/searchess
```

### C. Web UI

```bash
kubectl port-forward -n searchess svc/envoy 10000:10000 &
# Open browser: http://127.0.0.1:10000
# Expected: login form or automatic redirect to Keycloak
# Login: demo / demo
# Expected: chess UI loads, user "demo" shown in top-right auth bar
```

### D. Envoy JWT auth

```bash
# Public routes — no token needed:
curl -i http://127.0.0.1:10000/health
# Expected: 200 OK

curl -i http://127.0.0.1:10000/api/health
# Expected: 200 OK

# Protected route without token — expect 401:
curl -i http://127.0.0.1:10000/api/sessions
# Expected: 401 Unauthorized (Jwt is missing)
```

### E. Protected route with token

```bash
# Method 1: DevTools
# 1. Log in via browser at http://127.0.0.1:10000
# 2. Open DevTools → Network → any /api/ request
# 3. Copy the Authorization: Bearer <token> header value

# Method 2: password grant (requires enabling directAccessGrantsEnabled temporarily in Keycloak)
# Not recommended for demo. Use the browser method.

TOKEN="<paste bearer token here>"
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:10000/api/sessions
# Expected: 200 OK with sessions JSON
```

---

## SSH tunnel method (alternative to port-forward)

From the developer machine:
```bash
ssh -L 10000:localhost:10000 \
    -L 8080:localhost:8080 \
    -L 33001:localhost:33001 \
    chess@141.37.74.145
```

Then access:
- API + web-ui: `http://127.0.0.1:10000`
- Keycloak:     `http://127.0.0.1:8080`
- Grafana:      `http://127.0.0.1:33001`

---

## What is protected and what is public

| Path | Auth required | Notes |
|---|---|---|
| `GET /health` | No | Game service liveness passthrough |
| `GET /api/health` | No | Game service health (explicitly exempted) |
| `GET /api/*` (other) | **Yes — Bearer JWT** | Returns 401 if no/invalid token |
| `WS /ws/*` | No | WebSocket gameplay (game session must exist) |
| `GET /admin/migrations` | No (secured by app-level token) | Schema migration endpoint |
| `GET /*` | No | Web UI static files (nginx SPA) |

---

## Rollback

If Keycloak causes issues:
1. In ArgoCD UI or CLI, disable Keycloak pod (set replicas to 0 via override patch).
2. The other services (game-service, history-service, etc.) are unaffected — they don't depend on Keycloak.
3. The Envoy JWT filter will return 401 for all `/api/*` requests while Keycloak is down (JWKS unreachable). Public routes remain available.

---

## Known limitations

| Limitation | Impact |
|---|---|
| No TLS for Keycloak | Credentials sent in plaintext; SSH tunnel provides transport security |
| Token issuer hardcoded to `127.0.0.1:8080` | Tokens are only valid for the port-forward / SSH tunnel scenario; a public domain would require a new KC_HOSTNAME value and re-import of the realm |
| Web-ui image not built by CI yet | Manual build required before first deploy; see pre-flight step 3 |
| `keycloak-sealed-secret.yaml` placeholder | Must be replaced with real sealed values before Keycloak can start; see pre-flight step 1 |
| replicas: 1 | Single Keycloak pod; restarts cause ~60s auth downtime while KC re-starts |
