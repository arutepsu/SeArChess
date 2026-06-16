# Evaluation Tournament Demo

## Purpose

`EvaluationTournamentDemo` is the canonical end-to-end demo for the Bot Evaluation Arena.
It runs a representative mixed-family tournament — heuristic bots, a Stockfish depth/time
variant, and the SearchessAI service — then feeds the result into the existing Spark batch
analytics pipeline. This is the single recommended command for presentation and grading.

---

## Required Services

Two external services must be running or reachable before the demo starts:

| Service | How to start locally | Environment variable |
|---------|----------------------|----------------------|
| Stockfish binary | Install and set path | `STOCKFISH_PATH` |
| SearchessAI service | `sbt "aiService/run"` | `SEARCHESS_AI_BASE_URL` |

The demo runs a preflight check for both and fails with a clear message if either is missing.

---

## Environment Variables

| Variable | Default | Required |
|----------|---------|----------|
| `STOCKFISH_PATH` | — | Yes (or pass as CLI arg 1) |
| `SEARCHESS_AI_BASE_URL` | `http://localhost:8765` | No |
| `SEARCHESS_AI_TIMEOUT_MILLIS` | `5000` | No |

---

## Commands

### Run the evaluation tournament

```
# With environment variables set:
sbt demoEvaluationTournament

# With explicit Stockfish path and all options (outputPath repetitions maxPly includeSlow):
sbt "arenaDemoEvaluation/run C:\path\to\stockfish.exe target/arena/evaluation-tournament/game-events.jsonl 1 200 false"
```

Argument order: `[enginePath] [outputPath] [repetitions] [maxPly] [includeSlow]`

If `STOCKFISH_PATH` is set, the first positional argument becomes `outputPath`.

### Run Spark analytics on the tournament output

```
sbt demoEvaluationSparkAnalytics
```

Reads: `target/arena/evaluation-tournament/game-events.jsonl`
Writes: `target/spark-analytics-evaluation/`

### Recommended presentation flow

```
# Terminal 1: start ai-service
sbt "aiService/run"

# Terminal 2: run the full demo
set STOCKFISH_PATH=C:\path\to\stockfish.exe    # Windows
sbt demoEvaluationTournament
sbt demoEvaluationSparkAnalytics
```

---

## Default Participants

| botId | Family | Strategy |
|-------|--------|----------|
| random-bot | Heuristic | random |
| capture-first | Heuristic | capture-first |
| material-greedy | Heuristic | material-greedy |
| stockfish-depth-1 | UciEngine | depth-1 |
| stockfish-fast | UciEngine | movetime-100 |
| searchess-ai-v1 | AiService | searchess-ai |

6 bots × 5 opponents × 1 repetition = **30 matchups** by default.

### Why StockfishSlow is excluded by default

`stockfish-slow` uses 1000 ms per move. With 200 max ply per game, a single game can take
up to ~100 seconds. Adding it brings the field to 7 bots (42 matchups), which increases
worst-case runtime by approximately 20 minutes.

Pass `includeSlow=true` (5th positional arg) to include it when stronger analytics data
is more important than speed:

```
sbt "arenaDemoEvaluation/run C:\path\to\stockfish.exe target/arena/evaluation-tournament/game-events.jsonl 1 200 true"
```

---

## Expected Runtime

| Configuration | Matchups | Estimated runtime |
|---|---|---|
| Default (no slow) | 30 | ~2–5 minutes |
| includeSlow=true | 42 | ~25–35 minutes |
| repetitions=2, no slow | 60 | ~5–10 minutes |

These estimates assume Stockfish fast at 100 ms/move and SearchessAI at ≤500 ms/move.
Heuristic bots run in microseconds.

---

## Analytics Tables Produced

All eleven tables from `GameAnalytics.run` are populated by this tournament:

| Table | Notes |
|-------|-------|
| Leaderboard | All 6 bots ranked by score |
| Head-to-Head | 30 pairings × 2 directions |
| Termination Reasons | checkmate, stalemate, max-ply, 50-move-rule |
| Avg Game Length | Average ply and duration per pairing |
| Bot Family Comparison | Heuristic vs UciEngine vs AiService |
| Strategy Type Comparison | All strategy types in one table |
| White/Black Performance | Color advantage by bot |
| Fastest Winning Bots | Shortest winning games |
| Stockfish Configuration Comparison | depth-1 vs movetime-100 (and slow if included) |
| Family Matchups | Cross-family scoring aggregation |
| SearchessAI vs Opponents | SearchessAI-v1 results against each opponent |

---

## Architecture Notes

- `GameRunner`, `TournamentRunner`, and `arena-core` are unchanged.
- `arenaDemoEvaluation` depends on `arenaBotsAi`, `arenaBotsHeuristic`, `arenaBotsUci`, `arenaWriterJsonl`.
- No new analytics logic was added; this demo feeds the existing `GameAnalytics.run` pipeline.
- Spark reads only normalized `GameEvent` JSON — no SearchessAI or Stockfish classes involved.
