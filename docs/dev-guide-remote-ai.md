<<<<<<< HEAD
<<<<<<< HEAD
# Developer Guide - Remote AI Mode

This guide explains how to run the Scala Game Service against an external
host-run AI HTTP provider for local development, and what to verify before
treating the boundary as stable. The canonical Compose topology uses the Scala
`ai-service` container documented in
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).
=======
# Developer Guide — Remote AI Mode
=======
# Developer Guide - Remote AI Mode
>>>>>>> abcc8c8c (envoy + ai service prerp)

This guide explains how to run the Scala Game Service against the Python
`searchess-ai-service` for local development, and what to verify before
treating the boundary as stable.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)

---

## Services overview

| Service | Repo | Default address |
|---|---|---|
<<<<<<< HEAD
<<<<<<< HEAD
| Game Service (Scala, host-run) | `searchess` | `http://127.0.0.1:8080` |
| External AI provider (host-run) | optional separate process | `http://127.0.0.1:8765` |
| AI Service (Compose) | `searchess` | `http://ai-service:8765` |

The Game Service calls the AI service at `POST /v1/move-suggestions`. Remote
mode is the default runtime path; the AI service calls back nothing and remains
stateless.

For the full local container deployment, see
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).

---

## External provider on the host

If you are testing against an external AI provider, start it on the host:

```bash
# Example only; use the provider's own run command.
cd searchess-ai-service
uv run uvicorn searchess_ai.api.app:create_app \
  --factory --host 127.0.0.1 --port 8765 --reload
=======
| Game server (Scala) | `searchess` | `http://127.0.0.1:8080` |
| AI inference service (Python) | `searchess-ai-service` | `http://127.0.0.1:8765` |
=======
| Game Service (Scala, host-run) | `searchess` | `http://127.0.0.1:8080` |
| AI inference service (Python, host-run) | `searchess-ai-service` | `http://127.0.0.1:8765` |
| AI inference service (Compose) | `searchess-ai-service` | `http://ai-service:8765` |
>>>>>>> abcc8c8c (envoy + ai service prerp)

The Game Service calls the AI service at `POST /v1/move-suggestions`. Remote
mode is the default runtime path; the AI service calls back nothing and remains
stateless.

For the full local container deployment, see
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).

---

## Python service in Docker

Build and start the Python AI service from the AI repo:

```bash
cd searchess-ai-service
docker compose up --build
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
```

Verify it is up:

```bash
curl http://127.0.0.1:8765/health
<<<<<<< HEAD
<<<<<<< HEAD
```

When running the Scala Game Service directly on the host, set
`AI_REMOTE_BASE_URL=http://127.0.0.1:8765`. In the repo-level Compose setup,
Game reaches AI by service name at `http://ai-service:8765`.

The Compose AI Service is internal-only. It is not exposed through Envoy and is
not published as a host port by `docker-compose.yml`.

## Starting the Scala Game Service in remote AI mode

Remote AI mode is the default for the Game Service. When running directly via
sbt against a host-run AI provider:
=======
# → {"status":"ok","service":"searchess-ai-service","version":"0.1.0"}
=======
# {"status":"ok","service":"searchess-ai-service","version":"0.1.0"}
>>>>>>> abcc8c8c (envoy + ai service prerp)
```

When running the Scala Game Service directly on the host, set
`AI_REMOTE_BASE_URL=http://127.0.0.1:8765`. In the repo-level Compose setup,
Game reaches AI by service name at `http://ai-service:8765`.

### Inference backend

The Python container defaults to `INFERENCE_BACKEND=random`.

| Value | Behaviour |
|---|---|
| `random` | Picks a legal move at random; useful for integration testing |
| `fake` | Always picks `legalMoves[0]`; deterministic test backend |
| `openspiel` | Requires `open_spiel` installed; not bundled in the image |

---

## Python service without Docker

```bash
cd searchess-ai-service
uv run uvicorn searchess_ai.api.app:create_app \
  --factory --host 127.0.0.1 --port 8765 --reload
```

The `--factory` flag is required because the module uses a `create_app()`
factory rather than a module-level `app` instance.

---

## Starting the Scala Game Service in remote AI mode

<<<<<<< HEAD
For the two-container local deployment, see
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).

To run the Scala server directly via sbt, the Python service must already be
up (either via Docker or Option B above) before starting the Scala server.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
Remote AI mode is the default for the Game Service. When running directly via
sbt against a host-run Python service:
>>>>>>> abcc8c8c (envoy + ai service prerp)

```bash
cd searchess
AI_PROVIDER_MODE=remote \
AI_REMOTE_BASE_URL=http://127.0.0.1:8765 \
<<<<<<< HEAD
sbt "gameService/runMain chess.server.ServerMain"
=======
sbt "bootstrapServer/runMain chess.server.ServerMain"
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
```

At startup you will see:

<<<<<<< HEAD
<<<<<<< HEAD
```text
[chess] AI client: remote @ http://127.0.0.1:8765
<<<<<<< HEAD
=======
```
=======
```text
>>>>>>> abcc8c8c (envoy + ai service prerp)
[chess] AI provider: remote @ http://127.0.0.1:8765
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
>>>>>>> 14542117 (fix ai flow)
```

### Scala server env var reference

| Variable | Default | Accepted values |
|---|---|---|
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> abcc8c8c (envoy + ai service prerp)
| `AI_PROVIDER_MODE` | `remote` | `remote`, `local`, `disabled` |
| `AI_REMOTE_BASE_URL` | `http://ai-service:8765` | Any URL |
| `AI_TIMEOUT_MILLIS` | `2000` | Integer >= 1 |
| `AI_DEFAULT_ENGINE_ID` | unset | Any string |
<<<<<<< HEAD

`AI_PROVIDER_MODE=local` (also accepted as `local-deterministic`) wires the
in-process `LocalDeterministicAiClient` as a transitional/dev-only fallback.
`disabled` makes `/games/{id}/ai-move` return `422 AI_NOT_CONFIGURED`.

Inside Game Service, `/games/{id}/ai-move` depends on the single
`AiMoveSuggestionClient` port. The normal runtime implementation is
`RemoteAiMoveSuggestionClient`, which calls the configured AI service. The local
deterministic client is not selected unless `AI_PROVIDER_MODE=local` is set.
=======
| `AI_PROVIDER_MODE` | `local` | `local` · `disabled` · `remote` |
| `AI_REMOTE_BASE_URL` | *(required when remote)* | Any URL |
| `AI_TIMEOUT_MILLIS` | `2000` | Integer ≥ 1 |
| `AI_DEFAULT_ENGINE_ID` | *(unset)* | Any string |

`AI_PROVIDER_MODE=local` (also accepted as `local-deterministic`) wires the
in-process first-legal-move adapter.  `disabled` makes
`/games/{id}/ai-move` return `422 AI_NOT_CONFIGURED`.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======

`AI_PROVIDER_MODE=local` (also accepted as `local-deterministic`) wires the
in-process `LocalDeterministicAiClient` as a transitional/dev-only fallback.
`disabled` makes `/games/{id}/ai-move` return `422 AI_NOT_CONFIGURED`.
>>>>>>> abcc8c8c (envoy + ai service prerp)

Inside Game Service, `/games/{id}/ai-move` depends on the single
`AiMoveSuggestionClient` port. The normal runtime implementation is
`RemoteAiMoveSuggestionClient`, which calls the Python AI service. The local
deterministic client is not selected unless `AI_PROVIDER_MODE=local` is set.

---

## Verifying the end-to-end flow

**1. Create a HumanVsAI session.**

<<<<<<< HEAD
<<<<<<< HEAD
`"AI"` is not a valid controller value in REST v1. AI seats are determined
server-side by the `mode` field. For `HumanVsAI`, omit `blackController`; the
server assigns the Black seat to its configured AI client.
=======
`"AI"` is not a valid controller value in REST v1 — AI seats are determined
server-side by the `mode` field.  For `HumanVsAI` omit `blackController`
entirely; the server assigns the Black seat to its AI provider automatically.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
`"AI"` is not a valid controller value in REST v1. AI seats are determined
server-side by the `mode` field. For `HumanVsAI`, omit `blackController`; the
<<<<<<< HEAD
server assigns the Black seat to its configured AI provider.
>>>>>>> abcc8c8c (envoy + ai service prerp)
=======
server assigns the Black seat to its configured AI client.
>>>>>>> 14542117 (fix ai flow)

```bash
curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}' \
  | jq .
```

The response contains `session.gameId`; note that value.

<<<<<<< HEAD
<<<<<<< HEAD
**2. Submit a human move for White, then trigger the AI response for Black.**

```bash
# White moves e2 to e4
=======
**2. Submit a human move for White, then trigger the AI response for Black:**

```bash
# White moves e2→e4
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
**2. Submit a human move for White, then trigger the AI response for Black.**

```bash
# White moves e2 to e4
>>>>>>> abcc8c8c (envoy + ai service prerp)
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/moves \
  -H "Content-Type: application/json" \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}' \
  | jq .

# Ask the AI to respond for Black
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/ai-move | jq .
```

A successful AI-move response contains the move the AI chose and the updated
game state.

<<<<<<< HEAD
<<<<<<< HEAD
**3. Run the Scala integration tests** when an external provider is reachable on
the host at port `8765`:
=======
**3. Run the Scala integration tests** (requires Python service on port 8765):
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
**3. Run the Scala integration tests** when the Python service is reachable on
the host at port `8765`:
>>>>>>> abcc8c8c (envoy + ai service prerp)

```bash
sbt "adapterAi/testOnly chess.adapter.ai.remote.RemoteAiIntegrationSpec"
```

<<<<<<< HEAD
<<<<<<< HEAD
Those tests skip automatically when the provider is not reachable.
=======
Tests skip automatically when the Python service is not reachable.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
Those tests skip automatically when the Python service is not reachable.
>>>>>>> abcc8c8c (envoy + ai service prerp)

---

## Failure behaviour

<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
Remote AI client availability, timeout, and engine failures map to
`503 AI_PROVIDER_FAILED` at the Game Service REST boundary. Malformed provider
responses and illegal provider suggestions are rejected as
`422 AI_MOVE_REJECTED`.

| Scenario | `AIError` inside Scala adapter | Game Service response |
|---|---|---|
| AI provider not reachable | `Unavailable(...)` | `503 AI_PROVIDER_FAILED` |
| AI provider timeout | `Timeout(...)` | `503 AI_PROVIDER_FAILED` |
| AI provider returns `ENGINE_UNAVAILABLE` | `Unavailable(...)` | `503 AI_PROVIDER_FAILED` |
| AI provider returns `ENGINE_TIMEOUT` | `Timeout(...)` | `503 AI_PROVIDER_FAILED` |
| AI provider returns `ENGINE_FAILURE` | `EngineFailure(...)` | `503 AI_PROVIDER_FAILED` |
| AI provider returns malformed success JSON | `MalformedResponse(...)` | `422 AI_MOVE_REJECTED` |
| AI proposes an illegal move | `AITurnError.IllegalSuggestedMove(...)` | `422 AI_MOVE_REJECTED` |
| `AI_PROVIDER_MODE=remote` with blank `AI_REMOTE_BASE_URL` | startup config error | Server fails fast |

The Game Service always re-validates the move returned by the AI service
against its own legal-move list before applying it.
=======
All AI provider errors map to `503 AI_PROVIDER_FAILED` at the game server
REST boundary (`AITurnError.ProviderFailure` → `aiErrToHttpErr`).
=======
Provider availability, timeout, and engine failures map to
=======
Remote AI client availability, timeout, and engine failures map to
>>>>>>> 14542117 (fix ai flow)
`503 AI_PROVIDER_FAILED` at the Game Service REST boundary. Malformed provider
responses and illegal provider suggestions are rejected as
`422 AI_MOVE_REJECTED`.
>>>>>>> abcc8c8c (envoy + ai service prerp)

| Scenario | `AIError` inside Scala adapter | Game Service response |
|---|---|---|
| Python service not reachable | `Unavailable(...)` | `503 AI_PROVIDER_FAILED` |
| Python service timeout | `Timeout(...)` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_UNAVAILABLE` | `Unavailable(...)` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_TIMEOUT` | `Timeout(...)` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_FAILURE` | `EngineFailure(...)` | `503 AI_PROVIDER_FAILED` |
| Python returns malformed success JSON | `MalformedResponse(...)` | `422 AI_MOVE_REJECTED` |
| AI proposes an illegal move | `AITurnError.IllegalSuggestedMove(...)` | `422 AI_MOVE_REJECTED` |
| `AI_PROVIDER_MODE=remote` with blank `AI_REMOTE_BASE_URL` | startup config error | Server fails fast |

<<<<<<< HEAD
The game server **always re-validates** the move returned by the AI service
against its own legal-move list.
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
The Game Service always re-validates the move returned by the AI service
against its own legal-move list before applying it.
>>>>>>> abcc8c8c (envoy + ai service prerp)

---

## Health endpoint

`GET /health` on both services returns HTTP 200 when the process is running.
<<<<<<< HEAD
<<<<<<< HEAD
It is a basic liveness probe only. It does not check whether an inference
engine is loaded, whether FEN parsing is functional, or whether downstream
resources are available.

---

## What remains before AI Service is production-grade

| Area | Status |
|---|---|
| Real chess engine (Stockfish / lc0) | Not wired in the local Scala AI Service |
| FEN validation in AI service | `BAD_POSITION` error code exists but the local provider remains intentionally small |
| Authentication / API keys | No auth on either side |
| Health / readiness probes | `/health` is unconditional liveness only |
| Retry / circuit-breaker policy | No circuit breaker; Game maps provider failure explicitly |
=======
It is a **basic liveness probe only** — it does not check whether an inference
=======
It is a basic liveness probe only. It does not check whether an inference
>>>>>>> abcc8c8c (envoy + ai service prerp)
engine is loaded, whether FEN parsing is functional, or whether downstream
resources are available.

---

## What remains before AI Service is production-grade

| Area | Status |
|---|---|
| Real chess engine (Stockfish / lc0) | Not wired; only `random` and `fake` backends exist |
| FEN validation in AI service | `BAD_POSITION` error code exists but no FEN parser is called |
| Authentication / API keys | No auth on either side |
| Health / readiness probes | `/health` is unconditional liveness only |
<<<<<<< HEAD
| Retry / circuit-breaker policy | Callers retry once; no circuit-breaker wired |
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
| Retry / circuit-breaker policy | No circuit breaker; Game maps provider failure explicitly |
>>>>>>> abcc8c8c (envoy + ai service prerp)
| Observability (tracing, metrics) | No instrumentation |
