# SearchessAI-v1 Bot Adapter

## Overview

`SearchessAI-v1` is a `BotPlayer` implementation that selects moves by calling the existing
`ai-service` over HTTP. It plugs into the arena tournament infrastructure the same way
Stockfish bots do — no changes to `GameRunner`, `TournamentRunner`, or `arena-core`.

---

## Discovered Endpoint

| Property | Value |
|----------|-------|
| Method | `POST` |
| Path | `/v1/move-suggestions` |
| Default port | `8765` |
| Health check | `GET /health` |
| Service module | `backend/services/ai-service` |
| Contract module | `modules/ai-contract` |

---

## Request / Response Contract

**Request body:**
```json
{
  "requestId":  "<uuid>",
  "gameId":     "bot-arena-<uuid>",
  "sessionId":  "bot-arena-<uuid>",
  "sideToMove": "white",
  "fen":        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
  "legalMoves": [
    { "from": "e2", "to": "e4" },
    { "from": "d7", "to": "d8", "promotion": "q" }
  ],
  "engine":   { "engineId": null },
  "limits":   { "timeoutMillis": 5000 },
  "metadata": { "mode": "BotArena" }
}
```

**Response body (200):**
```json
{
  "requestId": "<echoed>",
  "move":      { "from": "e2", "to": "e4" }
}
```

Move encoding: `from`/`to` are lowercase algebraic squares (`"a1"`–`"h8"`), separate fields.
Promotion (optional): `"q"`, `"r"`, `"b"`, `"n"`.

---

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `SEARCHESS_AI_BASE_URL` | `http://localhost:8765` | AI service base URL |
| `SEARCHESS_AI_TIMEOUT_MILLIS` | `5000` | Per-move HTTP timeout |
| `STOCKFISH_PATH` | required | Stockfish binary for baseline bot in demo |

`AiServiceBotConfig.fromEnv()` reads these at bot construction time.

---

## Module Structure

```
backend/jobs/bot-arena/bots/ai/               ← arenaBotsAi module
  src/main/scala/chess/arena/bots/ai/
    AiServiceBotConfig.scala          ← config (baseUrl, timeout, botId, …)
    AiSquareNotation.scala            ← Move ↔ RemoteAiMoveDto conversion
    SearchessAiClient.scala           ← trait + SearchessAiClientError enum
    HttpSearchessAiClient.scala       ← JDK HttpClient implementation
    SearchessAiBot.scala              ← BotPlayer; calls client, validates move

backend/jobs/bot-arena/demo-ai/               ← arenaDemoAi module
  src/main/scala/chess/arena/demo/
    SearchessAiTournamentDemo.scala   ← runnable demo (5 bots, 20 matchups)
```

**Dependencies of `arenaBotsAi`:**
- `arenaCore` — `BotPlayer`, `BotProfile`
- `notation` — FEN export (`FenNotationFacade`)
- `aiContract` — DTOs + codec (`RemoteAiMoveDto`, `RemoteAiJson`); depends only on ujson, no http4s

**Not depended on:** `adapterAi`, `aiService`, `gameService`, http4s.

---

## Bot Profile

| Field | Value |
|-------|-------|
| `botId` | `searchess-ai-v1` |
| `family` | `ai-service` (JSON) / `BotFamily.AiService` |
| `strategyType` | `searchess-ai` |
| `engineType` | `none` |
| `modelVersion` | `v1` |

These fields flow through `GameStarted` / `GameFinished` events unchanged and are visible in all Spark analytics tables.

---

## Demo Commands

```
# Requires: ai-service running and STOCKFISH_PATH set

# Start ai-service (in a separate terminal):
sbt "aiService/run"

# Run SearchessAI tournament (5 bots, 20 matchups, repetitions=1):
sbt demoSearchessAiTournament

# With explicit args (stockfishPath, outputPath, repetitions, maxPly):
sbt "arenaDemoAi/run C:\path\to\stockfish.exe target/arena/searchess-ai-tournament/game-events.jsonl 1 300"

# Run Spark analytics on the tournament output:
sbt demoSearchessAiSparkAnalytics
```

If `STOCKFISH_PATH` is set, `demoSearchessAiTournament` requires no extra arguments.

If the AI service is unreachable, the demo prints a clear error before starting:
```
Searchess AI service not reachable at http://localhost:8765/health.
Start ai-service (`sbt "aiService/run"`) or set SEARCHESS_AI_BASE_URL.
```

---

## Architecture Boundaries

- `GameRunner` and `TournamentRunner` see only `BotPlayer` — no HTTP awareness.
- `arena-core` has no HTTP dependency.
- `SearchessAiBot.selectMove` always validates the returned move against
  `GameStateRules.legalMoves(state)`. The arena is the authority; the AI only suggests.
- Spark analytics reads only normalized `GameEvent` JSON — no `SearchessAiBot` class imported.

---

## Spark Analytics

All existing tables (`leaderboard`, `head-to-head`, etc.) include `searchess-ai-v1` automatically
because they operate on raw JSON fields.

An additional table **SearchessAI vs Opponents** is produced:

| Column | Description |
|--------|-------------|
| `opponentBotId` | The bot SearchessAI played against |
| `opponentFamily` | Family of the opponent |
| `games` | Total games between them |
| `searchessAiWins` | SearchessAI wins |
| `draws` | Draws |
| `losses` | SearchessAI losses |
| `score` | Total score (1.0/0.5/0.0) |
| `winRate` | wins / games |
| `avgGameLength` | Average total ply |

CSV folder: `searchess-ai-comparison/`

---

## Testing

```
sbt testArenaBotsAi
```

Unit tests (no live service):
- `AiSquareNotationSpec` — Move ↔ DTO encoding; legal move lookup; illegal move rejection
- `SearchessAiBotSpec` — Profile metadata; fake-client happy path; illegal AI response; connection errors; captured request fields
- `HttpSearchessAiClientSpec` — JSON codec round-trips; error response parsing; `describe` messages

Integration test (skipped unless `SEARCHESS_AI_BASE_URL` is set):
- `SearchessAiIntegrationSpec` — live HTTP call; verifies a legal move is returned from initial position

Spark analytics tests (fake JSONL fixture, no service needed):
- `GameAnalyticsSpec` — `computeSearchessAiComparison` win/loss/draw counts; opponent filter; draw accounting
