# Lichess Bridge — Live Enablement Runbook

This document covers the operator steps required to bring `lichess-bridge-service` live
on the `uni-server-registry` cluster for the first time (Phase 3A: validation only, no
challenge acceptance).

---

## A. Prerequisites

Before proceeding, confirm all of the following:

- [ ] Lichess BOT account `arutepsu2` exists and is **upgraded to BOT status**
  (via https://lichess.org/api/bot/account/upgrade — requires one-time API call with the token)
- [ ] The token held by `arutepsu2` has the following Lichess API scopes:
  - `bot:play` (required for Bot API: stream, move, chat)
- [ ] The token is stored securely and **never committed to the repository**
- [ ] The cluster can reach `lichess.org` (outbound HTTPS, port 443)
- [ ] `ai-service` is deployed and reachable at `http://ai-service:8765`
  (verify: `kubectl exec -n searchess <any-pod> -- curl -s http://ai-service:8765/health`)
- [ ] `kubeseal` CLI is installed and configured for the target cluster's sealing key

---

## B. Secret creation

The `LICHESS_BOT_TOKEN` env var is injected from `searchess-secrets / lichess-bot-token`
(see `deployment/k8s/base/lichess-bridge-service/deployment.yaml`).

The `searchess-secrets` SealedSecret lives at:
```
deployment/k8s/overlays/uni-server-registry/sealed-secret.yaml
```

**The real token must never appear in the repository or in any log output.**

### Step 1 — Add the token to the existing SealedSecret

The project uses `bitnami/sealed-secrets`. You must re-seal the secret with the new key
added, using the cluster's public key.

```bash
# 1. Fetch the cluster's current sealing certificate (run once per cluster)
kubeseal --fetch-cert \
  --controller-name=sealed-secrets \
  --controller-namespace=kube-system \
  > /tmp/searchess-sealing-cert.pem

# 2. Create a plain Secret YAML in memory (never write the real token to a file you might commit)
kubectl create secret generic searchess-secrets \
  --namespace searchess \
  --from-literal=lichess-bot-token=REPLACE_WITH_REAL_TOKEN \
  --dry-run=client \
  -o yaml \
| kubeseal \
    --cert /tmp/searchess-sealing-cert.pem \
    --format yaml \
  > /tmp/lichess-sealed-patch.yaml

# 3. Inspect the output — it will contain the encrypted value for lichess-bot-token only.
#    Merge the encryptedData.lichess-bot-token line from /tmp/lichess-sealed-patch.yaml
#    into the existing sealed-secret.yaml under spec.encryptedData.
#
# The final sealed-secret.yaml must contain ALL existing keys PLUS the new lichess-bot-token key.
# Existing keys: bot-worker-api-key, grafana-admin-password, migration-admin-token, postgres-password
```

> **Warning:** Do not paste the raw token (`REPLACE_WITH_REAL_TOKEN`) into any shell history,
> CI environment variable, Slack message, or commit message.

### Step 2 — Commit the updated sealed-secret.yaml

```bash
git add deployment/k8s/overlays/uni-server-registry/sealed-secret.yaml
git commit -m "ops: add lichess-bot-token to searchess-secrets sealed secret"
```

The commit is safe because `sealed-secret.yaml` contains only the encrypted ciphertext,
not the raw token.

---

## C. BOT service account vs linked user accounts

### What `LICHESS_BOT_USERNAME` is

`LICHESS_BOT_USERNAME: "arutepsu2"` is an **infrastructure identity** — the central Lichess BOT
service account that `lichess-bridge-service` uses to connect to Lichess and play games on behalf
of the Searchess platform.

It is:
- A dedicated Lichess account upgraded to BOT status via `POST /api/bot/account/upgrade`
- Controlled exclusively by the `lichess-bridge-service` deployment
- Configured in `sealed-secret.yaml` via `lichess-bot-token` (not in Keycloak)
- Used only for the Bot API endpoints (`/api/bot/...`)

It is **not**:
- A Searchess application user
- A Keycloak principal or realm user
- A default account for human users
- Shared with or derived from any human user's identity

The developer's personal Lichess account (`arutepsu`) is a separate identity. Lichess policy
requires a fresh, dedicated account for BOT upgrade; `arutepsu2` was created for this purpose.

### What linked Lichess accounts are

Searchess users can register through Keycloak and then link their own personal Lichess accounts
in the Settings page. These linked Lichess accounts are human user identities stored in
`user-service`. They have no connection to `arutepsu2` or to the bridge's BOT token.

### How the two identities interact at challenge time

When challenge acceptance is enabled (Phase 3B — see §H), `lichess-bridge-service` dynamically
authorizes each challenger by calling `user-service`. The challenger's Lichess username must be
linked to a Searchess user account via the Settings page.

**No static `LICHESS_ALLOWED_CHALLENGERS` list is needed or used** in the standard Phase 3B
configuration. `user-service` is the authoritative source of linked Lichess usernames, and the
bridge calls it for each incoming challenge. The static allowlist exists only as a legacy override
path that is inactive when `LICHESS_REQUIRE_LINKED_CHALLENGER=true` (the default).

---

## D. Safe first deployment

**Do not run `kubectl apply` or `argocd sync` manually.**

1. Ensure the `feature/lichess` branch patches are merged to `main` (or the GitOps-tracked branch).
2. CI will build `searchess/lichess-bridge-service` and write the real `sha-<git-sha>` tag into
   `deployment/k8s/overlays/uni-server-registry/kustomization.yaml` under `images`.
   Until that happens the tag is `sha-placeholder` and the pod will fail to pull — that is expected.
3. Once CI has pushed the image and updated the tag, ArgoCD will detect OutOfSync and auto-sync
   (selfHeal: true is already configured).
4. The sealed-secret commit (Step B above) must be merged **before** ArgoCD syncs the Deployment,
   otherwise the pod starts but `LICHESS_BOT_TOKEN` is empty and the bridge logs a warning and
   sits idle (this is safe — the worker simply does not start).

---

## E. Validation commands after sync

Run these from any machine with `kubectl` access to the cluster:

```bash
# Confirm the pod is running
kubectl get pods -n searchess -l app=lichess-bridge-service

# Expected output:
# NAME                                     READY   STATUS    RESTARTS   AGE
# lichess-bridge-service-<hash>            1/1     Running   0          <age>

# Tail recent logs (look for worker_token_valid and no errors)
kubectl logs -n searchess deployment/lichess-bridge-service --since=10m

# Open a local port-forward for validation (Ctrl-C to stop when done)
kubectl port-forward -n searchess deployment/lichess-bridge-service 8090:8090
```

In a separate terminal while the port-forward is active:

```bash
# Liveness probe
curl -s http://localhost:8090/health
# Expected: {"status":"ok","service":"lichess-bridge-service"}

# Bridge status — check enabled, configured, no active games
curl -s http://localhost:8090/internal/lichess/status | python3 -m json.tool
# Expected:
#   "enabled": true
#   "botUsernameConfigured": true
#   "tokenConfigured": true
#   "workerRunning": true
#   "acceptChallenges": false
#   "activeGamesCount": 0
#   "phase": "2B-2"

# Challenge policy — confirm conservative defaults
curl -s http://localhost:8090/internal/lichess/policy | python3 -m json.tool
# Expected:
#   "acceptChallenges": false
#   "acceptRated": false
#   "allowedVariants": ["standard"]
#   "maxConcurrentGames": 1
#   "minClockSeconds": 180
#   "maxClockSeconds": 600

# Token + account validation — calls real Lichess API
curl -s http://localhost:8090/internal/lichess/validate | python3 -m json.tool
# Expected:
#   "status": "ok"
#   "botProfile": {
#     "id": "arutepsu2",
#     "username": "arutepsu2",
#     "title": "BOT",   ← or "isBot": true
#     "isBot": true
#   }
```

A successful `/validate` response with `isBot: true` confirms:
- The token is valid
- The account `arutepsu2` has BOT status
- The bridge can reach `lichess.org`

---

## F. Challenge-AI spike note

`POST /internal/lichess/challenge-ai/spike` is a development-time spike endpoint.
It calls `POST /api/challenge/ai` on Lichess, which is documented for **normal user accounts**.
With a BOT-upgraded account token, this call is expected to return an error (HTTP 4xx).

**Do not treat failure on this endpoint as a blocker.**
The only endpoint that matters for Phase 3A validation is `/internal/lichess/validate`.

---

## G. Phase 3B prerequisites: internal API key

Before enabling challenge acceptance, the service-to-service secret must be provisioned.

`lichess-bridge-service` calls `user-service` to verify that a challenger's Lichess account is
linked to a Searchess user. This call uses a shared secret header (`X-Internal-Api-Key`).

### Add `user-service-internal-api-key` to the SealedSecret

```bash
# 1. Generate a secure random key (example — use your own generator)
openssl rand -hex 32

# 2. Seal a new secret containing only the new key
kubectl create secret generic searchess-secrets \
  --namespace searchess \
  --from-literal=user-service-internal-api-key=REPLACE_WITH_GENERATED_KEY \
  --dry-run=client \
  -o yaml \
| kubeseal \
    --cert /tmp/searchess-sealing-cert.pem \
    --format yaml \
  > /tmp/internal-api-key-patch.yaml

# 3. Merge the encryptedData.user-service-internal-api-key entry
#    into deployment/k8s/overlays/uni-server-registry/sealed-secret.yaml
#    alongside the existing keys.

# 4. Commit
git add deployment/k8s/overlays/uni-server-registry/sealed-secret.yaml
git commit -m "ops: add user-service-internal-api-key to searchess-secrets"
```

**Both services read the same key:**
- `user-service` uses it to verify incoming `X-Internal-Api-Key` header requests
- `lichess-bridge-service` sends it when calling `GET /internal/lichess/challenge-auth/{username}`

**Fail-closed behavior:**
If either service starts without the key (key absent or empty), the `/challenge-auth` endpoint
returns `401` and the bridge declines all challenges. This is intentional and safe.

---

## H. Phase 3B: enabling challenge acceptance

Only after §G (internal API key sealed and deployed) and §E (validate returns `isBot: true`):

### How the authorization works

When a Lichess user challenges `arutepsu2`:

1. `lichess-bridge-service` receives the `ChallengeCreated` event containing `challengerUsername`
2. Policy checks pass (unrated, standard variant, clock in range, max games not reached)
3. Bridge calls `GET http://user-service:8082/internal/lichess/challenge-auth/{challengerUsername}`
   with `X-Internal-Api-Key: <secret>`
4. `user-service` looks up whether that Lichess username is linked to any Searchess user
5. If linked → bridge accepts; if not linked → bridge declines

**The challenger must first:**
1. Register on Searchess
2. Link their Lichess account in Settings → this creates an `ExternalAccountLink` in `user-service`

No static username list is needed or used. Any Searchess user with a linked Lichess account is
automatically eligible to challenge `arutepsu2` once challenge acceptance is enabled.

### GitOps PR to enable challenge acceptance

Create a GitOps PR with only this change to the ConfigMap patch:

```yaml
# In: deployment/k8s/overlays/uni-server-registry/patches/lichess-bridge-service-live.yaml
# Change:
LICHESS_ACCEPT_CHALLENGES: "true"
# LICHESS_REQUIRE_LINKED_CHALLENGER: "true" is already the default — no change needed
# Keep everything else unchanged:
LICHESS_ACCEPT_RATED: "false"
MAX_CONCURRENT_GAMES: "1"
```

**Do NOT add `LICHESS_ALLOWED_CHALLENGERS`** — that is a legacy static override that is not
needed when `LICHESS_REQUIRE_LINKED_CHALLENGER=true` (the default). The dynamic user-service
authorization replaces it.

### Validation after enabling challenge acceptance

1. Have a Searchess user link their Lichess account in Settings
2. From that Lichess account, challenge `arutepsu2` on Lichess
3. Expected: bridge accepts and a game starts
4. Expected logs: `challenge_accepted`, then `game_started`, then AI move submissions

If the challenger is not linked: bridge declines with `generic` reason (expected).
If user-service is unavailable: bridge declines (fail-closed, expected).

---

## I. Rollback

To disable the bridge without removing the GitOps patch:

```bash
# Option 1 — scale to 0 in the overlay (requires a commit)
# Edit patches/lichess-bridge-service-replicas.yaml: replicas: 0

# Option 2 — emergency manual scale-down (temporary, Argo will revert on next sync)
kubectl scale deployment lichess-bridge-service -n searchess --replicas=0
```

To fully remove live enablement, revert the Phase 3A commits and let ArgoCD re-sync.
