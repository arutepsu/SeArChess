# Developer Guide - Remote AI Mode

This guide explains how to run the Scala Game Service against an external
host-run AI HTTP provider for local development, and what to verify before
treating the boundary as stable. The canonical Compose topology uses the Scala
`ai-service` container documented in
[`docs/dev-guide-container-local.md`](dev-guide-container-local.md).

---

## Services overview

| Service | Repo | Default address |
|---|---|---|
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
```

Verify it is up:

```bash
curl http://127.0.0.1:8765/health
```

When running the Scala Game Service directly on the host, set
`AI_REMOTE_BASE_URL=http://127.0.0.1:8765`. In the repo-level Compose setup,
Game reaches AI by service name at `http://ai-service:8765`.

The Compose AI Service is internal-only. It is not exposed through Envoy and is
not published as a host port by `docker-compose.yml`.

## Starting the Scala Game Service in remote AI mode

Remote AI mode is the default for the Game Service. When running directly via
sbt against a host-run AI provider:

```bash
cd searchess
AI_PROVIDER_MODE=remote \
AI_REMOTE_BASE_URL=http://127.0.0.1:8765 \
sbt "gameService/runMain chess.server.ServerMain"
```

At startup you will see:

```text
[chess] AI client: remote @ http://127.0.0.1:8765
```

### Scala server env var reference

| Variable | Default | Accepted values |
|---|---|---|
| `AI_PROVIDER_MODE` | `remote` | `remote`, `local`, `disabled` |
| `AI_REMOTE_BASE_URL` | `http://ai-service:8765` | Any URL |
| `AI_TIMEOUT_MILLIS` | `2000` | Integer >= 1 |
| `AI_DEFAULT_ENGINE_ID` | unset | Any string |

`AI_PROVIDER_MODE=local` (also accepted as `local-deterministic`) wires the
in-process `LocalDeterministicAiClient` as a transitional/dev-only fallback.
`disabled` makes `/games/{id}/ai-move` return `422 AI_NOT_CONFIGURED`.

Inside Game Service, `/games/{id}/ai-move` depends on the single
`AiMoveSuggestionClient` port. The normal runtime implementation is
`RemoteAiMoveSuggestionClient`, which calls the configured AI service. The local
deterministic client is not selected unless `AI_PROVIDER_MODE=local` is set.

---

## Verifying the end-to-end flow

**1. Create a HumanVsAI session.**

`"AI"` is not a valid controller value in REST v1. AI seats are determined
server-side by the `mode` field. For `HumanVsAI`, omit `blackController`; the
server assigns the Black seat to its configured AI client.

```bash
curl -s -X POST http://127.0.0.1:8080/sessions \
  -H "Content-Type: application/json" \
  -d '{"mode":"HumanVsAI"}' \
  | jq .
```

The response contains `session.gameId`; note that value.

**2. Submit a human move for White, then trigger the AI response for Black.**

```bash
# White moves e2 to e4
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/moves \
  -H "Content-Type: application/json" \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}' \
  | jq .

# Ask the AI to respond for Black
curl -s -X POST http://127.0.0.1:8080/games/{gameId}/ai-move | jq .
```

A successful AI-move response contains the move the AI chose and the updated
game state.

**3. Run the Scala integration tests** when an external provider is reachable on
the host at port `8765`:

```bash
sbt "adapterAi/testOnly chess.adapter.ai.remote.RemoteAiIntegrationSpec"
```

Those tests skip automatically when the provider is not reachable.

---

## Failure behaviour

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

---

## Health endpoint

`GET /health` on both services returns HTTP 200 when the process is running.
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
| Observability (tracing, metrics) | No instrumentation |
