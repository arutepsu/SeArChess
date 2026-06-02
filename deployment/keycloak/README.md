# Keycloak — Auth for SeArChess

Keycloak provides OpenID Connect authentication for SeArChess.

- **Local Compose**: Keycloak runs alongside the application stack. Fully working.
- **Kubernetes (university server)**: Keycloak is deployed in the `searchess` namespace. Access is via `kubectl port-forward` or SSH tunnel.

---

## Port map

| Service | Host port | Notes |
|---|---|---|
| k3d API (k3d-searchess-serverlb) | `10000` | Kubernetes load balancer — Envoy edge |
| Compose Envoy API (local auth stack) | `11000` | Host 11000 → container 10000 |
| Keycloak (local Compose) | `8080` | Admin console + OIDC |
| Keycloak (K8s via port-forward) | `8080` | `kubectl port-forward -n searchess svc/keycloak 8080:8080` |
| Web UI dev server | `5173` | Vite, `npm run dev:auth` or `npm run dev:deployed` |

---

## Auth model

| Layer | Mechanism |
|---|---|
| Browser → Keycloak | Authorization Code Flow + PKCE (S256) |
| Frontend client | `searchess-web` — public, no client secret |
| Token audience | `searchess-api` injected by audience mapper |
| Envoy → backend | JWT validation against Keycloak JWKS |
| Public routes | `/health`, `/api/health`, `/ws/*`, `/admin/migrations`, `/*` (web-ui static) |
| Protected routes | `/api/*` (except `/api/health`) |

---

## Issuer vs JWKS endpoint (important)

The issuer in the JWT `iss` claim must exactly match the string configured in Envoy's `jwt_authn` provider.

| Deployment | Browser Keycloak URL | Token issuer | Envoy issuer config | JWKS (internal) |
|---|---|---|---|---|
| Local Compose | `http://localhost:8080` | `http://localhost:8080/realms/searchess` | `http://localhost:8080/realms/searchess` | `http://keycloak:8080/...` |
| Kubernetes (port-forward/SSH) | `http://127.0.0.1:8080` | `http://127.0.0.1:8080/realms/searchess` | `http://127.0.0.1:8080/realms/searchess` | `http://keycloak:8080/...` |

Envoy inside the cluster cannot use `127.0.0.1` to reach Keycloak (that would be Envoy's own loopback). It fetches JWKS via the K8s DNS name `keycloak:8080`. The `issuer` field in Envoy config validates the token claim only — it does not determine where JWKS is fetched from.

---

## Local Compose — run

```bash
# 1. Start Compose auth stack
docker compose -f deployment/compose/docker-compose.yml up \
  keycloak envoy game-service postgres mongo redis history-service ai-service python-ai-service

# 2. Start Web UI with auth mode (separate terminal, from repo root)
cd apps/web-ui
npm install
npm run dev:auth        # loads .env.auth → VITE_KEYCLOAK_URL=http://localhost:8080

# 3. Open browser
open http://localhost:5173
# → browser redirects to Keycloak login
# → login: demo / demo
# → app loads with demo user authenticated
```

**Compose admin console:** `http://localhost:8080` — credentials `admin`/`admin` (local dev only).

---

## Kubernetes deployment — first-time setup

### Step 1: Seal Keycloak credentials

```bash
# From repo root, with kubectl context pointing at the university cluster:
bash scripts/seal-keycloak-secrets.sh
# → prompts for bootstrap admin username/password + DB password
# → writes deployment/k8s/overlays/uni-server-registry/keycloak-sealed-secret.yaml

git add deployment/k8s/overlays/uni-server-registry/keycloak-sealed-secret.yaml
git commit -m "chore: seal keycloak credentials for university server"
git push origin main
```

Argo CD will sync the `SealedSecret/keycloak-secrets` on the next cycle.

### Step 2: Init the Keycloak Postgres database

Run once before Keycloak starts (idempotent — safe to re-run):

```bash
kubectl apply -f deployment/k8s/base/postgres/job-keycloak-db-init.yaml
kubectl wait --for=condition=complete -n searchess job/postgres-init-keycloak --timeout=120s
kubectl logs -n searchess job/postgres-init-keycloak
```

This creates:
- Database: `keycloak`
- User: `keycloak` with the password from `Secret/keycloak-secrets`
- Grants all privileges on database and schema

### Step 3: Sync via Argo CD

After sealing secrets and running the DB init Job, Argo CD will sync Keycloak:

```bash
kubectl get applications -n argocd
kubectl get pods -n searchess -l app=keycloak
```

Keycloak starts in `start` mode (not `start-dev`) and imports the realm on each boot.
First startup takes 60–90 seconds while Keycloak builds its provider registry.

---

## Kubernetes — access Keycloak

Keycloak is not exposed through Envoy (keeps admin console off the public port). Access via port-forward:

```bash
kubectl port-forward -n searchess svc/keycloak 8080:8080
# Then: http://127.0.0.1:8080
```

Or add to the SSH tunnel from the university server:
```bash
ssh -L 10000:localhost:10000 -L 8080:localhost:8080 -L 33001:localhost:33001 chess@141.37.74.145
```

---

## Kubernetes — run Web UI against deployed backend

```bash
# In apps/web-ui/:
npm run dev:deployed
# loads .env.deployed:
#   VITE_KEYCLOAK_URL=http://127.0.0.1:8080
#   VITE_API_BASE_URL=http://127.0.0.1:10000
```

Requires both port-forwards (or SSH tunnel) to be active.

When Envoy is exposed through an SSH tunnel at `http://localhost:18000`, open the deployed Web UI at that origin. The `searchess-web` Keycloak client allows this localhost tunnel redirect for deployed testing; keep this as a dev/test redirect and do not bypass Keycloak.

---

## Kubernetes — reconcile Web UI client redirects

Keycloak imports `realm-searchess.json` at startup, but changed client fields are not guaranteed to update an already-created realm. The university-server overlay therefore includes a normal Kubernetes CronJob rather than relying on a one-shot Argo CD hook:

- `deployment/k8s/overlays/uni-server-registry/keycloak-searchess-web-reconcile-job.yaml`
- CronJob name: `keycloak-reconcile-searchess-web-client`
- Schedule: every 5 minutes (`*/5 * * * *`)
- Client reconciled: `searchess-web`
- Credentials source: `Secret/keycloak-secrets` keys `bootstrap-admin-username` and `bootstrap-admin-password`

The CronJob calls the Keycloak Admin REST API, fetches the current `searchess-web` client, appends any missing required redirect URIs and web origins, and PUTs the merged client representation back only when changes are needed. Existing redirect URIs and web origins are preserved.

Manual Keycloak UI or `kubectl exec` patches are useful for emergency diagnosis only. They are not the long-term source of truth because Argo CD and Keycloak realm import behavior can drift from manual changes. The CronJob is normal GitOps-managed cluster state, so Argo keeps it present and Kubernetes reruns it on schedule.

To force an immediate reconciliation run:

```bash
kubectl create job -n searchess --from=cronjob/keycloak-reconcile-searchess-web-client keycloak-reconcile-searchess-web-client-manual-$(date +%s)
```

Verify the live client through the Admin API after port-forwarding Keycloak:

```bash
kubectl port-forward -n searchess svc/keycloak 8080:8080

ADMIN_USERNAME="$(kubectl get secret -n searchess keycloak-secrets -o jsonpath='{.data.bootstrap-admin-username}' | base64 -d)"
read -r -s -p "Keycloak admin password: " ADMIN_PASSWORD
echo

TOKEN="$(
  curl -sS -X POST http://127.0.0.1:8080/realms/master/protocol/openid-connect/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode grant_type=password \
    --data-urlencode client_id=admin-cli \
    --data-urlencode username="$ADMIN_USERNAME" \
    --data-urlencode password="$ADMIN_PASSWORD" \
    | jq -r '.access_token'
)"

CLIENT_UUID="$(
  curl -sS -H "Authorization: Bearer $TOKEN" \
    'http://127.0.0.1:8080/admin/realms/searchess/clients?clientId=searchess-web' \
    | jq -r '.[] | select(.clientId == "searchess-web") | .id'
)"

curl -sS -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/admin/realms/searchess/clients/$CLIENT_UUID" \
  | jq '{redirectUris, webOrigins}'
```

The deployed Web UI SSH tunnel origin must be present:

- Redirect URIs: `http://localhost:18000/*`, `http://127.0.0.1:18000/*`
- Web origins: `http://localhost:18000`, `http://127.0.0.1:18000`

---

## Realm summary

| Item | Value |
|---|---|
| Realm | `searchess` |
| Frontend client | `searchess-web` (public, PKCE S256, standardFlow) |
| API resource | `searchess-api` (bearer-only, for audience) |
| Audience mapper | `searchess-api-audience` on `searchess-web` → adds `searchess-api` to `aud` |
| Role | `searchess-user` |
| Demo user | `demo` / `demo` (documented demo credential) |

The realm JSON is at two locations that must stay in sync:
- `deployment/keycloak/realm-searchess.json` — used by Docker Compose import
- `deployment/k8s/base/keycloak/realm-searchess.json` — used by Kustomize ConfigMap (Kubernetes)

When updating the realm, update both files.

**Valid redirect URIs (searchess-web):**
- `http://localhost:5173/*` — local Vite dev
- `http://127.0.0.1:5173/*` — local Vite dev (alternate)
- `http://127.0.0.1:10000/*` — K8s via SSH tunnel / port-forward
- `http://localhost:10000/*` — K8s via SSH tunnel / port-forward
- `http://localhost:18000/*` — deployed Web UI via SSH tunnel
- `http://127.0.0.1:18000/*` — deployed Web UI via SSH tunnel (alternate)

**Valid web origins (searchess-web):**
- `http://localhost:5173`
- `http://127.0.0.1:5173`
- `http://127.0.0.1:10000`
- `http://localhost:10000`
- `http://localhost:18000`
- `http://127.0.0.1:18000`

---

## Validate local Compose

```bash
# Public health routes — no token needed
curl -i http://localhost:11000/health       # 200 OK
curl -i http://localhost:11000/api/health   # 200 OK

# Protected route without token — expect 401
curl -i http://localhost:11000/api/sessions  # 401 Unauthorized

# Protected route with token — expect 200
# (get token from browser DevTools → Network → any /api/ request → Authorization header)
curl -i -H "Authorization: Bearer <token>" http://localhost:11000/api/sessions  # 200 OK
```

## Validate Kubernetes

```bash
# 1. Start port-forwards
kubectl port-forward -n searchess svc/envoy 10000:10000 &
kubectl port-forward -n searchess svc/keycloak 8080:8080 &

# 2. Keycloak health
curl http://127.0.0.1:8080/health/ready
curl http://127.0.0.1:8080/realms/searchess/.well-known/openid-configuration

# 3. Envoy public routes
curl -i http://127.0.0.1:10000/health       # 200 OK
curl -i http://127.0.0.1:10000/api/health   # 200 OK
curl -i http://127.0.0.1:10000/api/sessions # 401 Unauthorized

# 4. Web UI
open http://127.0.0.1:10000   # → served by web-ui nginx, redirects to Keycloak login
# login: demo / demo
# After login: open DevTools → Network → any /api/ request
TOKEN="<paste from Authorization header>"
curl -i -H "Authorization: Bearer $TOKEN" http://127.0.0.1:10000/api/sessions  # 200 OK
```

---

## Security decisions

| Decision | Reason |
|---|---|
| `start` mode (not `start-dev`) | Production-shaped startup; validates config, uses Postgres, builds provider registry |
| HTTP-only (no TLS) | All access via SSH tunnel or port-forward; TLS termination not available on this server; cert-manager + domain not yet configured |
| `KC_HOSTNAME=http://127.0.0.1:8080` | Token issuer matches the browser-visible address via tunnel; Envoy validates the same issuer string |
| Postgres backend (not embedded H2) | Persistent data survives pod restarts; H2 loses realm data on restart |
| SealedSecrets | Bootstrap credentials encrypted at rest in Git; never plaintext in any committed file |
| Separate `keycloak-secrets` Secret | Avoids mixing with existing `searchess-secrets`; can be replaced independently |
| Keycloak NOT behind Envoy | Admin console not exposed on the public Envoy port; port-forward is the access path |
| replicas: 1 | University VM has 4 GB RAM; single replica uses ~256–512 MB heap; multiple replicas would require shared session storage |

---

## JWKS endpoint

```
# Internal (used by Envoy inside K8s):
http://keycloak:8080/realms/searchess/protocol/openid-connect/certs

# External (used for debugging, requires port-forward):
http://127.0.0.1:8080/realms/searchess/protocol/openid-connect/certs
```
