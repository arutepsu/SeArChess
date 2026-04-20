# Developer Guide — Remote AI Mode

This guide explains how to run the Scala game server against the Python
`searchess-ai-service` for local development, and what to verify before
treating the boundary as stable.

---

## Services overview

| Service | Repo | Default address |
|---|---|---|
| Game server (Scala) | `searchess` | `http://127.0.0.1:8080` |
| AI inference service (Python) | `searchess-ai-service` | `http://127.0.0.1:8765` |

The game server calls the AI service at `POST /v1/move-suggestions` when
`AI_PROVIDER_MODE=remote`.  The AI service calls back nothing; it is
stateless.

---

## Option A — Python service in Docker (recommended)

Build and start the Python AI service as a container:

```bash
cd searchess-ai-service
docker compose up --build
```

Verify it is up:

```bash
curl http://127.0.0.1:8765/health
# → {"status":"ok","service":"searchess-ai-service","version":"0.1.0"}
```

The container binds `8765` on the host.  When running the Scala game server
directly on the host, set `AI_REMOTE_BASE_URL=http://127.0.0.1:8765`.

### Inference backend

The container defaults to `INFERENCE_BACKEND=random` (picks a legal move at
random).  To override:

```bash
INFERENCE_BACKEND=fake docker compose up --build
```

| Value | Behaviour |
|---|---|
| `random` | Picks a legal move at random — good for integration testing |
| `fake` | Always picks `legalMoves[0]` — deterministic, used in unit tests |
| `openspiel` | Requires `open_spiel` installed; not bundled in the image |

---

## Option B — Python service without Docker

```bash
cd searchess-ai-service
uv run uvicorn searchess_ai.api.app:create_app \
  --factory --host 127.0.0.1 --port 8765 --reload
```

The `--factory` flag is required because the module uses a `create_app()`
factory rather than a module-level `app` instance.

---

## Starting the Scala game server in remote AI mode

For the two-container local deployment, see
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).

To run the Scala server directly via sbt, the Python service must already be
up (either via Docker or Option B above) before starting the Scala server.

```bash
cd searchess
AI_PROVIDER_MODE=remote \
AI_REMOTE_BASE_URL=http://127.0.0.1:8765 \
sbt "bootstrapServer/runMain chess.server.ServerMain"
```

At startup you will see:

```
[chess] AI provider: remote @ http://127.0.0.1:8765
```

### Scala server env var reference

| Variable | Default | Accepted values |
|---|---|---|
| `AI_PROVIDER_MODE` | `local` | `local` · `disabled` · `remote` |
| `AI_REMOTE_BASE_URL` | *(required when remote)* | Any URL |
| `AI_TIMEOUT_MILLIS` | `2000` | Integer ≥ 1 |
| `AI_DEFAULT_ENGINE_ID` | *(unset)* | Any string |

`AI_PROVIDER_MODE=local` (also accepted as `local-deterministic`) wires the
in-process first-legal-move adapter.  `disabled` makes
`/games/{id}/ai-move` return `422 AI_NOT_CONFIGURED`.

---

## Verifying the end-to-end flow

**1. Create a HumanVsAI session.**

`"AI"` is not a valid controller value in REST v1 — AI seats are determined
server-side by the `mode` field.  For `HumanVsAI` omit `blackController`
entirely; the server assigns the Black seat to its AI provider automatically.

```bash
curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}' \
  | jq .
```

The response contains `session.gameId`; note that value.

**2. Submit a human move for White, then trigger the AI response for Black:**

```bash
# White moves e2→e4
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/moves \
  -H "Content-Type: application/json" \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}' \
  | jq .

# Ask the AI to respond for Black
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/ai-move | jq .
```

A successful AI-move response contains the move the AI chose and the updated
game state.

**3. Run the Scala integration tests** (requires Python service on port 8765):

```bash
sbt "adapterAi/testOnly chess.adapter.ai.remote.RemoteAiIntegrationSpec"
```

Tests skip automatically when the Python service is not reachable.

---

## Failure behaviour

All AI provider errors map to `503 AI_PROVIDER_FAILED` at the game server
REST boundary (`AITurnError.ProviderFailure` → `aiErrToHttpErr`).

| Scenario | `AIError` inside Scala adapter | Game server response |
|---|---|---|
| Python service not reachable / timeout | `EngineFailure("timeout")` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_UNAVAILABLE` | `EngineFailure("ENGINE_UNAVAILABLE: …")` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_TIMEOUT` | `EngineFailure("ENGINE_TIMEOUT: …")` | `503 AI_PROVIDER_FAILED` |
| Python returns `ENGINE_FAILURE` | `EngineFailure("ENGINE_FAILURE: …")` | `503 AI_PROVIDER_FAILED` |
| AI proposes an illegal move | `AITurnError.MoveFailed(…)` | `422 AI_MOVE_REJECTED` |
| `AI_REMOTE_BASE_URL` missing at startup | — | Server throws; fails fast |

The game server **always re-validates** the move returned by the AI service
against its own legal-move list.

---

## Health endpoint

`GET /health` on both services returns HTTP 200 when the process is running.
It is a **basic liveness probe only** — it does not check whether an inference
engine is loaded, whether FEN parsing is functional, or whether downstream
resources are available.  Do not treat a 200 response as a readiness or
capability signal.

---

## What remains before AI Service is a real extracted deployable

| Area | Status |
|---|---|
| Real chess engine (Stockfish / lc0) | Not wired — only `random` and `fake` backends exist |
| FEN validation in AI service | `BAD_POSITION` error code exists but no FEN parser is called |
| Authentication / API keys | No auth on either side |
| Scala game server Docker packaging | Local/dev Compose packaging exists; not production-hardened |
| Persistent storage | Scala Game Service can use mounted SQLite; AI service remains stateless |
| Health / readiness probes | `/health` is unconditional liveness only |
| Retry / circuit-breaker policy | Callers retry once; no circuit-breaker wired |
| Observability (tracing, metrics) | No instrumentation |
