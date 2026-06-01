# Keycloak — Local Auth (Docker Compose only)

Local Keycloak setup for SeArChess. **Not part of the Kubernetes university deployment.**  
Keycloak is not deployed to the university server in the current task.

---

## Port map

| Service | Host port | Notes |
|---|---|---|
| k3d API (k3d-searchess-serverlb) | `10000` | Kubernetes load balancer — **untouched** |
| Compose Envoy API (local auth stack) | `11000` | Host 11000 → container 10000 |
| Keycloak | `8080` | Admin console + OIDC |
| Web UI dev server | `5173` | Vite, `npm run dev:auth` |

Compose Envoy listens on host port **11000** to avoid collision with the k3d container already bound to 10000.

---

## Auth model

| Layer | Mechanism |
|---|---|
| Browser → Keycloak | Authorization Code Flow + PKCE (S256) |
| Frontend client | `searchess-web` — public, no client secret |
| Token audience | `searchess-api` injected by audience mapper |
| Envoy → backend | JWT validation against Keycloak JWKS |
| Public routes | `/health`, `/api/health`, `/ws/*`, `/admin/migrations` |
| Protected routes | `/api/*` (except `/api/health`) |

---

## Issuer vs JWKS endpoint (important)

The browser authenticates against **`http://localhost:8080`**, so the `iss` claim in every token is:

```
http://localhost:8080/realms/searchess
```

Envoy runs inside Docker Compose and cannot use `localhost`. It fetches the JWKS from the internal DNS name:

```
http://keycloak:8080/realms/searchess/protocol/openid-connect/certs
```

Envoy's `jwt_authn` is configured with `issuer: http://localhost:8080/realms/searchess` (must match the token) and `remote_jwks` pointing to `keycloak:8080` (internal fetch). These are intentionally different values.

---

## Run locally

```bash
# 1. Start Compose auth stack
docker compose -f deployment/compose/docker-compose.yml up \
  keycloak envoy game-service postgres mongo redis history-service ai-service python-ai-service

# 2. Start Web UI with auth mode (separate terminal, from repo root)
cd apps/web-ui
npm install
npm run dev:auth        # loads .env.auth → VITE_API_BASE_URL=http://localhost:11000

# 3. Open browser
open http://localhost:5173
# → browser redirects to Keycloak login
# → login: demo / demo
# → app loads, "demo" shown in top-right auth bar
```

---

## Keycloak admin console

URL: http://localhost:8080  
Credentials: `admin` / `admin` (local dev bootstrap only — never use in shared environments)

---

## Realm summary

| Item | Value |
|---|---|
| Realm | `searchess` |
| Frontend client | `searchess-web` (public, PKCE S256, standardFlow) |
| API resource | `searchess-api` (bearer-only, for audience) |
| Audience mapper | `searchess-api-audience` on `searchess-web` → adds `searchess-api` to `aud` |
| Role | `searchess-user` |
| Demo user | `demo` / `demo` |

---

## Validate

### Public health routes — no token needed

```bash
curl -i http://localhost:11000/health
# Expected: 200 OK

curl -i http://localhost:11000/api/health
# Expected: 200 OK
```

### Protected route without token — expect 401

```bash
curl -i http://localhost:11000/api/sessions
# Expected: 401 Unauthorized (JWT required)
```

### Protected route with browser token — expect 200

After logging in via the browser (`demo`/`demo`), open DevTools → Network.  
Any request to `/api/*` should include:

```
Authorization: Bearer eyJ...
```

The response should be 200 and contain the sessions JSON payload.

---

## Env file summary

| File | Script | API base |
|---|---|---|
| `.env.auth` | `npm run dev:auth` | `http://localhost:11000` (Compose Envoy) |
| `.env.deployed` | `npm run dev:deployed` | `http://localhost:10000` (k3d via SSH tunnel) |
| _(none)_ | `npm run dev` | `http://localhost:10000` (hardcoded default) |

---

## JWKS endpoint

```
http://localhost:8080/realms/searchess/protocol/openid-connect/certs
```

---

## Legacy password-grant script (dev debug only)

`scripts/keycloak-get-token.sh` and `scripts/keycloak-get-token.ps1` use the Resource Owner Password Credentials grant. This is **disabled by default** on `searchess-web`. It can be enabled temporarily in Keycloak admin for inspecting token contents. Do not use it as the primary auth mechanism.

---

## Future: Kubernetes deployment

Keycloak is not deployed to the university server yet.  
Future steps (not part of this task):
- Add Keycloak to the Kubernetes overlay
- Update Envoy manifests with `jwt_authn` config
- Update `validRedirectUris` for the university server hostname
- Update the `issuer` in Envoy to match the production Keycloak URL
