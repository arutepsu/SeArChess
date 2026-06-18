# Lichess Bridge Architecture

## Context

Searchess has two distinct bot-related subsystems that must not be confused:

| Service | Role | External traffic |
|---|---|---|
| `bot-service` | Internal bot worker — polls game-service for pending AI turns in **Human vs Searchess Bot** games and submits moves via AI service | None — internal only |
| `lichess-bridge-service` | External bridge — relays challenges and moves between Lichess and Searchess | Outbound to `lichess.org` |

`bot-service` is a pure internal worker. It never contacts Lichess. Its deployment name is `bot-service`; its internal identity is `searchess-bot`. This name must not be changed to `lichess-bot` or any Lichess-scoped name.

`lichess-bridge-service` handles all Lichess-facing traffic.

---

## AI_SERVICE_URL decision

`AI_SERVICE_URL=http://ai-service:8765` is **correct**.

The Scala `ai-service` is the internal move-brain interface. It exposes `/v1/move-suggestions` and, when configured, proxies to `python-ai-service` (INFERENCE_BACKEND) for neural-net or random selection. Both `bot-service` and `lichess-bridge-service` call `ai-service` — not `python-ai-service` directly. The Python service is an implementation detail of the Scala AI service.

---

## Phase 1 — COMPLETE

Phase 1 established the skeleton with no real Lichess integration:

- HTTP surface: `GET /health` and `GET /internal/lichess/status`
- Config case class reading all relevant env vars; `LICHESS_BRIDGE_ENABLED` defaults to `false`
- Stub traits: `LichessClient`, `LichessEventStream`, `ChallengePolicy` — no network calls
- Kubernetes Deployment with `replicas: 0`; `LICHESS_BOT_TOKEN` secret ref marked `optional: true`
- CI build job gated on `backend/services/lichess-bridge-service/**` and `Dockerfile.lichess-bridge-service`

---

## Phase 2A — COMPLETE

Phase 2A makes the service capable of validating a real Lichess BOT token and running a connectivity spike. It does **not** implement a game-playing loop.

### What was added

**Config (`LichessBridgeConfig`):**
- `lichessBotToken: Option[String]` — read from `LICHESS_BOT_TOKEN`; never logged
- `tokenConfigured: Boolean` — derived; safe to expose in status
- `botUsernameConfigured: Boolean` — derived from `lichessBotUsername`
- `requireToken(): Either[String, String]` — validates token presence when bridge is enabled

**Client (`LichessClient[F[_]]` trait + `LichessHttpClient`):**
- Real HTTP client using `java.net.http.HttpClient` (same as bot-service)
- Connect timeout: 10 s; read timeout: 15 s per request
- `getBotProfile(token)` — calls `GET https://lichess.org/api/account`
- `validateToken(token)` — delegates to getBotProfile
- `challengeAi(token, level, clockLimit, clockIncrement)` — calls `POST https://lichess.org/api/challenge/ai`
- Error ADT: `Unauthorized`, `RateLimited`, `NetworkError`, `UnexpectedResponse`
- JSON parsing via ujson (already in project)

**Routes:**
- `GET /internal/lichess/status` — extended: now includes `lichessApiBaseUrl`, `tokenConfigured`, `botUsernameConfigured`, `maxConcurrentGames`, `aiServiceUrl`, `phase`; token value never included
- `GET /internal/lichess/validate` — calls real Lichess API; handles disabled/no-token/ok/errors
- `POST /internal/lichess/challenge-ai/spike` — creates a real level-1 AI game on Lichess; requires enabled + token + username

**K8s:** `LICHESS_BOT_TOKEN` secret ref was already present in Phase 1 deployment.yaml. No change needed.

### What is NOT implemented in Phase 2A

- No persistent event stream (`lichess.org/api/stream/event`)
- No automatic challenge acceptance
- No move submission loop (game worker)
- No incoming game-start handling
- No tournament logic
- No Lichess OAuth on behalf of users

---

## Phase 2B-1 — COMPLETE

### Streaming and challenge handling foundation

Phase 2B-1 adds the persistent Lichess event stream, conservative challenge policy, and the background worker that manages them. The full AI move loop is deferred to Phase 2B-2.

**New files:**

| File | Role |
|---|---|
| `LichessDomain.scala` | Typed domain models: `LichessBotEvent`, `LichessChallenge`, `LichessTimeControl`, `LichessGameRef`, `LichessGameEvent`, `ActiveGame`, `ParseError` |
| `LichessNdjsonParser.scala` | Pure, IO-free NDJSON parsers: `parseBotEventLine`, `parseGameEventLine`. Unknown event types produce `Unknown(type, raw)` — never crash the stream. Malformed lines produce `Left(ParseError)`. |
| `LichessStreamClient.scala` | `LichessEventStream[F]` + `LichessGameStream[F]` interfaces; `JdkLichessStreamClient` using JDK `BodyHandlers.ofInputStream` + `fs2.io.readInputStream`. Three stream exceptions: `LichessAuthException` (fatal), `LichessRateLimitException`, `LichessNetworkException`. |
| `ChallengePolicy.scala` | `ChallengeDecision` ADT (`Accept` \| `Decline(reason)`); `DeclineReason` ADT with Lichess API string mappings; `DefaultChallengePolicy`. |
| `WorkerState.scala` | cats-effect `Ref`-held state; safe for HTTP routes to read concurrently. |
| `LichessBridgeWorker.scala` | cats-effect `Resource` managing the background event-stream fiber with exponential-backoff reconnection. |

**LichessClient extended:** `acceptChallenge(token, challengeId)` and `declineChallenge(token, challengeId, reason)` added to trait and `LichessHttpClient`.

**Challenge policy — default rules:**

| Condition | Default | Accept? |
|---|---|---|
| `LICHESS_BRIDGE_ENABLED=false` | false | Decline (BridgeDisabled) |
| `LICHESS_ACCEPT_CHALLENGES=false` | false | Decline (ChallengesDisabled) |
| Active games ≥ `MAX_CONCURRENT_GAMES` | 1 | Decline (MaxGamesReached) |
| `rated=true` and `LICHESS_ACCEPT_RATED=false` | false | Decline (RatedNotAllowed) |
| Variant not in `LICHESS_ALLOWED_VARIANTS` | standard | Decline (VariantNotAllowed) |
| Clock < `LICHESS_MIN_CLOCK_SECONDS` or > `LICHESS_MAX_CLOCK_SECONDS` | 180–600 | Decline (ClockOutOfRange) |
| Challenger not in `LICHESS_ALLOWED_CHALLENGERS` (when non-empty) | empty | Decline (ChallengerNotAllowed) |

Decline reasons map to Lichess API strings (`generic`, `later`, `casual`, `standard`, `tooFast`, `tooSlow`).

**Worker lifecycle:**
1. If disabled → no-op; HTTP server still starts (useful for debugging).
2. If enabled, no token → log warning; no-op.
3. If enabled + token → validate token once. If invalid → log error; update `lastError`; no-op.
4. If valid → set `running=true`, start background fiber on `/api/stream/event`.
5. On `ChallengeCreated` → evaluate policy → `acceptChallenge` or `declineChallenge`.
6. On `GameStart` → register `ActiveGame` in `WorkerState`.
7. On `GameFinish` → remove from `WorkerState`.
8. Transient error → exponential backoff (1 s → 2 s → … max 30 s) then reconnect.
9. Fatal `401`/`403` → no reconnect; surface in `lastError`.
10. Service shutdown → Resource release cancels fiber; then HTTP server stops.

**Streaming implementation notes:**
- JDK `HttpClient.send(req, BodyHandlers.ofInputStream())` via `IO.blocking` (CE blocking thread pool).
- Body read with `fs2.io.readInputStream[IO]` → `fs2.text.utf8.decode` → `fs2.text.lines`.
- No new library dependencies — `fs2-io` is already a transitive dep of `http4s-ember-server`.

**Routes added/changed:**
- `GET /internal/lichess/status` — extended with `workerRunning`, `acceptChallenges`, `activeGames` list, `activeGamesCount`, `lastEventAt`, `lastError`.
- `GET /internal/lichess/policy` — current policy config; no secrets.

**What is NOT implemented in Phase 2B-1:**
- No per-game board stream consumer (frame only — `GameStart` logs but doesn't open a stream).
- No AI move calls.
- No move submission.

---

## Phase 2B-2 — COMPLETE

Per-game board stream + Searchess AI move loop + move submission.

### New files

| File | Role |
|---|---|
| `TurnDetector.scala` | Pure, IO-free logic: `isBotTurn(botSide, moveCount, status)`, `countMoves(movesStr)`, `determineBotSide(botUsername, white, black)`, `isTerminal(status)` |
| `GamePositionAdapter.scala` | Converts Lichess stream data (initialFen + UCI history) into domain types for ai-service: `toGameState`, `toLegalMoveDtos`, `toCurrentFen`, `toSideToMove` |
| `AiServiceClient.scala` | `AiServiceClient[F[_]]` trait + `JdkAiServiceClient` implementation; domain types `AiMoveRequest`, `UciMove`, `AiError` |
| `GameFiberManager.scala` | Separate `Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]]`; `startGame`, `stopGame`, `cancelAll`; duplicate-start guard |
| `GameLoopHandler.scala` | Per-game event loop: opens board stream, handles `GameFull`/`GameState`/`ChatLine`/`OpponentGone`/`Unknown`, calls AI, submits move |

### Modified files

| File | Change |
|---|---|
| `LichessClient.scala` | Added `submitMove(token, gameId, move): F[Either[LichessError, Unit]]` |
| `LichessHttpClient.scala` | Implemented `submitMove` via `POST /api/board/game/{gameId}/move/{move}` |
| `LichessDomain.scala` | `ActiveGame` extended with `lastMoveCount`, `lastSubmittedMove`, `lastGameEventAt`; helpers `withSubmittedMove`, `withGameEventAt` |
| `WorkerState.scala` | Added `updateGameMeta(gameId, f)` |
| `LichessBridgeWorker.scala` | Accepts `GameFiberManager`; `GameStart` → `startGame`; `GameFinish` → `stopGame`; resource release calls `cancelAll` |
| `LichessBridgeWiring.scala` | Wires `JdkAiServiceClient`, creates `GameFiberManager`, passes to worker |
| `LichessBridgeRoutes.scala` | Status route now `phase=2B-2`; active game objects include `lastMoveCount`, `lastSubmittedMove`, `lastGameEventAt` |
| `build.sbt` | `lichessBridgeService` now `.dependsOn(observability, notation, aiContract)` |

### Turn detection

`TurnDetector.isBotTurn(botSide, moveCount, status)` implements the parity rule:

| `moveCount % 2` | Side to move |
|---|---|
| 0 (even) | White |
| 1 (odd) | Black |

Terminal statuses (`mate`, `resign`, `stalemate`, `timeout`, `draw`, `outoftime`, `cheat`, `noStart`, `unknownFinish`, `variantEnd`, `aborted`) always block move submission, regardless of turn.

`moveCount` is computed from the space-separated UCI move string in the Lichess event. An empty or blank string → 0 moves.

### Position reconstruction (GamePositionAdapter)

The Lichess board stream provides `initialFen` (or `"startpos"`) plus a space-separated sequence of UCI moves. The ai-service requires the **current** FEN and the list of legal moves in `RemoteAiMoveDto` format. `GamePositionAdapter` bridges this gap:

1. Parse `initialFen` (treating `"startpos"` as the standard starting FEN) via `FenNotationFacade.parseAndImport`.
2. Replay each UCI move in sequence via `GameStateRules.applyMove`.
3. Compute legal moves for the resulting state via `GameStateRules.legalMoves`.
4. Serialize the current state back to FEN via `FenNotationFacade.executeExport`.
5. Convert `Move` objects to `RemoteAiMoveDto(from.toString, to.toString, promotion)`.

If any step fails (malformed FEN, illegal UCI move in sequence), the error is logged and no move is submitted. The fiber does not crash.

### AI service boundary

The move brain is `ai-service` at `http://ai-service:8765` (`POST /v1/move-suggestions`). This service returns the best `RemoteAiMoveDto`. The bridge converts the response to a UCI string (`from + to + optionalPromotion`) and submits it to Lichess.

`python-ai-service` is **not** called directly. `ai-service` is the sole internal boundary.

### Error and fallback policy

| Failure | Behavior |
|---|---|
| FEN parse / UCI replay fails | Log; skip move; fiber continues |
| AI returns `Left(AiError.*)` | Log; skip move; fiber continues |
| AI throws exception | Log; skip move; fiber continues |
| `submitMove` returns `Left(...)` | Log; no retry; fiber continues |
| Parse error in game stream | Log; continue reading next events |
| Game stream terminates | Fiber ends naturally; `GameFiberManager` entry remains until `GameFinish` clears it |

There is **no random or illegal fallback move**. If the AI cannot produce a move the turn is skipped silently (with a log entry).

### Duplicate-move prevention

`GameLoopHandler` maintains an internal `lastMoves: String` initialized to the sentinel `"__initial__"`. Before calling the AI, it checks whether the incoming `moves` string equals `lastMoves`. If so the event is skipped. The sentinel ensures the first event (empty moves string) is always processed.

### Live enablement

Live play requires an operator action:
1. Create the `searchess-secrets / lichess-bot-token` secret in the cluster.
2. Set `LICHESS_BRIDGE_ENABLED=true` in the Deployment.
3. Scale replicas from 0 to 1.

None of these steps are automated. The Deployment yaml remains at `replicas: 0` and `LICHESS_BRIDGE_ENABLED=false`.

### What is NOT implemented in Phase 2B-2

- No tournament service (separate future phase).
- No Lichess OAuth on behalf of users.
- No public-facing routes (all routes remain under `/internal`).

---

## Current flow (Phase 2B-2)

```
Lichess                         Searchess cluster
──────────────────────────────────────────────────────────────────
                                lichess-bridge-service (worker)
  Event stream (NDJSON)  ←───  streamBotEvents() [JdkLichessStreamClient]
  Accept challenge       ───►  ChallengePolicy.evaluate() → acceptChallenge()
  Decline challenge      ───►  ChallengePolicy.evaluate() → declineChallenge()
  Game start/finish      ───►  GameFiberManager.startGame / stopGame

                                per-game fiber [GameLoopHandler]
  Game stream (NDJSON)   ←───  streamGame() GET /api/bot/game/stream/{id}
  GameFull / GameState   ───►  TurnDetector → GamePositionAdapter
  Move suggestion        ───►  ai-service:8765 POST /v1/move-suggestions
  Submit move            ───►  submitMove() POST /api/bot/game/{id}/move/{uci}
```

---

## Phase 3A — COMPLETE (live on uni-server-registry)

Controlled live enablement of `lichess-bridge-service` with the dedicated BOT account `arutepsu2`.

- `LICHESS_BRIDGE_ENABLED=true`, `LICHESS_BOT_USERNAME=arutepsu2`, `LICHESS_ACCEPT_CHALLENGES=false`
- `replicas: 1` in the `uni-server-registry` overlay only (base remains `replicas: 0`)
- Validated: `GET /internal/lichess/validate` returns `isBot: true`, `id: arutepsu2`
- Challenge acceptance intentionally disabled for Phase 3A; game loop runs but no new games start

**Identity boundary established in Phase 3A:**

| Identity | Source | Purpose |
|---|---|---|
| `arutepsu2` | `LICHESS_BOT_USERNAME` / `searchess-secrets/lichess-bot-token` | Central Lichess BOT service account — NOT a Searchess user |
| Human linked accounts | `user-service.external_account_links` | Per-user Lichess links from Settings page |

`arutepsu2` is an infrastructure credential. It has no Keycloak principal, no `UserProfile`, and no entry in `user-service`. Human users link their own Lichess accounts separately through the Searchess Settings OAuth PKCE flow.

---

## Phase 3B — COMPLETE

Dynamic linked-user challenge authorization: bridge asks `user-service` whether a challenger's Lichess username is linked to a Searchess user before accepting.

### Challenge authorization flow

```
Lichess challenge event (challengerUsername = X)
  ↓
lichess-bridge-service
  ChallengePolicy.evaluate(challenge)
    1. bridge disabled?  → Decline
    2. acceptChallenges=false? → Decline
    3. max games reached? → Decline
    4. rated and acceptRated=false? → Decline
    5. variant not allowed? → Decline
    6. clock out of range? → Decline
    7. requireLinkedChallenger=true?
       → GET http://user-service:8082/internal/lichess/challenge-auth/{X}
          X-Internal-Api-Key: <USER_SERVICE_INTERNAL_API_KEY>
          ├─ Authorized  → Accept
          ├─ NotLinked   → Decline(LinkedUserRequired("not_linked"))
          └─ Unavailable → Decline(LinkedUserRequired("user_service_unavailable"))
    8. requireLinkedChallenger=false and allowedChallengers non-empty?
       → static allowlist check (legacy admin override)
       else → Accept
```

The user-service call is **last in the chain** — obvious policy violations (rated, wrong variant, clock) are rejected before a network call is made.

### New files

| File | Role |
|---|---|
| `backend/services/lichess-bridge-service/.../UserServiceClient.scala` | `UserServiceClient[F[_]]` trait; `ChallengeAuthResult` ADT (`Authorized`, `NotLinked`, `Unavailable`); `JdkUserServiceClient` HTTP impl; `JdkUserServiceClient.parseAuthResponse` pure parser |
| `backend/services/user-service/.../InternalLichessRoutes.scala` | `GET /internal/lichess/challenge-auth/{username}` — protected by `X-Internal-Api-Key`; returns `{"allowed": true/false, ...}` |

### Modified files

| File | Change |
|---|---|
| `ExternalAccountLinkRepository` | Added `findByLichessUsername(username): Either[String, Option[ExternalAccountLink]]` |
| `SlickExternalAccountLinkRepository` | Implemented `findByLichessUsername` with case-insensitive Slick query |
| `UserServiceConfig` | Added `internalApiKey: String` loaded from `USER_SERVICE_INTERNAL_API_KEY` |
| `UserServiceWiring` | Combines `UserRoutes <+> InternalLichessRoutes` |
| `LichessBridgeConfig` | Added `userServiceUrl`, `requireLinkedChallenger` (default: `true`), `userServiceApiKey` |
| `ChallengePolicy` | `DefaultChallengePolicy` takes `UserServiceClient`; `LinkedUserRequired` added to `DeclineReason`; `evaluatePure` gated on `!requireLinkedChallenger` for static allowlist |
| `LichessBridgeWiring` | Wires `JdkUserServiceClient(config.userServiceUrl, config.userServiceApiKey)` |
| `LichessStubs` | `AuthorizedUserServiceClient`, `NotLinkedUserServiceClient`, `UnavailableUserServiceClient`, `ControllableUserServiceClient` |

### Fail-closed policy

| Condition | Behavior |
|---|---|
| user-service returns `allowed: false` | Decline(LinkedUserRequired("not_linked")) |
| user-service is unreachable | Decline(LinkedUserRequired("user_service_unavailable")) |
| user-service returns non-200 | Decline(LinkedUserRequired("user_service_unavailable")) |
| user-service returns invalid JSON | Decline(LinkedUserRequired("user_service_unavailable")) |
| `USER_SERVICE_INTERNAL_API_KEY` empty | `/challenge-auth` returns 401; bridge declines (Unavailable) |

The bridge never accepts a challenge when the authorization result is ambiguous.

### Service-to-service authentication

| Component | Role |
|---|---|
| Header | `X-Internal-Api-Key` (same pattern as game-service `X-Bot-Api-Key`) |
| Secret key | `searchess-secrets / user-service-internal-api-key` |
| Env var (server) | `USER_SERVICE_INTERNAL_API_KEY` (user-service reads it) |
| Env var (client) | `USER_SERVICE_INTERNAL_API_KEY` (lichess-bridge-service reads it) |
| Route exposure | NOT exposed through Envoy; cluster-internal only on port 8082 |

Both services read the same secret from `searchess-secrets`. The operator must add `user-service-internal-api-key` to `sealed-secret.yaml` (see runbook).

### New config keys (Phase 3B)

| Key | Default | Where |
|---|---|---|
| `USER_SERVICE_URL` | `http://user-service:8082` | `lichess-bridge-service` ConfigMap |
| `LICHESS_REQUIRE_LINKED_CHALLENGER` | `true` | `lichess-bridge-service` ConfigMap |
| `USER_SERVICE_INTERNAL_API_KEY` | (empty; must be set via secret) | both deployments, `optional: true` |

### Enabling challenge acceptance (Phase 3B activation)

After the `user-service-internal-api-key` secret is sealed and deployed, enable in the `uni-server-registry` overlay:

```yaml
LICHESS_ACCEPT_CHALLENGES: "true"
LICHESS_REQUIRE_LINKED_CHALLENGER: "true"
```

No static username list is needed. A human challenger only needs to:
1. Register on Searchess
2. Link their Lichess account in Settings
3. Challenge `arutepsu2` on Lichess

---

## Updated flow (Phase 3B)

```
Lichess challenge event (challengerUsername = X)
  ↓
ChallengePolicy: rated / variant / clock checks
  ↓ (passes)
UserServiceClient.authorizeChallenger(X)
  GET /internal/lichess/challenge-auth/X  (X-Internal-Api-Key)
  ↓
user-service: findByLichessUsername(X)
  ├─ found  → {"allowed": true,  "reason": "linked_user"}
  └─ absent → {"allowed": false, "reason": "not_linked"}
  ↓
Authorized → acceptChallenge(token, challengeId)
NotLinked  → declineChallenge(token, challengeId, "generic")
```

---

## Future UI modes

The Lichess Bridge placeholder section contains three future modes:

| UI id | Description |
|---|---|
| `LichessBridgeHumanVsLichess` | A human Searchess user challenges a Lichess player directly |
| `LichessBridgeAiVsLichess` | The Searchess AI plays on Lichess on behalf of the logged-in user |
| `LichessBridgeSpectate` | Watch and analyse a live Lichess game inside the Searchess board UI |

All three require `lichess-bridge-service` to be running and `LICHESS_BRIDGE_ENABLED=true`.

---

## What is not in scope

- **Tournament service**: tournament brackets, pairings, and standings require a dedicated `tournament-service`. This is a separate future phase and is not part of the Lichess Bridge work.
- **Lichess OAuth on behalf of users**: the current bridge design uses a single bot account token. Per-user Lichess OAuth (already partially wired in `user-service`) is a separate concern.

---

## Secret handling

`LICHESS_BOT_TOKEN` is mounted from `searchess-secrets / lichess-bot-token` with `optional: true`. The Deployment starts (replicas=0) and the pod would start without the secret being present. The secret must only be created in the cluster when Phase 2B is ready to go live; it must never be committed to the repository.

The token value is **never** logged, printed, or included in any HTTP response.

---

## Naming conventions

- Kubernetes resource: `lichess-bridge-service`
- sbt project: `lichessBridgeService`
- Docker image (base): `searchess/lichess-bridge-service`
- GHCR image: `ghcr.io/arutepsu/searchess-lichess-bridge-service`
- Scala package: `chess.lichessbridge`
- Service name in logs: `lichess-bridge-service`
