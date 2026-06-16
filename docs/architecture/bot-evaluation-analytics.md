# Bot Evaluation Analytics — Phase 7A

## Overview

Phase 7A extends the Bot Evaluation Arena with two additional Stockfish configurations
(StockfishFast and StockfishSlow) and a richer set of Spark batch analytics queries that
compare bot families, strategy types, color advantage, and Stockfish configurations.

---

## Bot Catalog (Phase 7A)

| botId              | family     | strategyType  | engineType | movetimeMillis / depth |
|--------------------|------------|---------------|------------|------------------------|
| random-bot         | Heuristic  | random        | none       | —                      |
| capture-first      | Heuristic  | capture-first | none       | —                      |
| material-greedy    | Heuristic  | material-greedy | none     | —                      |
| stockfish-depth-1  | UciEngine  | depth-1       | stockfish  | depth 1                |
| stockfish-depth-3  | UciEngine  | depth-3       | stockfish  | depth 3                |
| stockfish-fast     | UciEngine  | movetime-100  | stockfish  | 100 ms per move        |
| stockfish-slow     | UciEngine  | movetime-1000 | stockfish  | 1000 ms per move       |

### StockfishFast and StockfishSlow

Both use the existing `StockfishBot` / `ProcessUciEngine` UCI adapter — no new UCI
implementation was added.

- `StockfishBot.fast(enginePath)` → `UciBotConfig.stockfishMoveTime("stockfish-fast", …, 100L)`
- `StockfishBot.slow(enginePath)` → `UciBotConfig.stockfishMoveTime("stockfish-slow", …, 1000L)`

The strategy type string is derived automatically from the movetime value:
`movetime-<N>` (e.g. `movetime-100`, `movetime-1000`).

---

## Demo Commands

### Run tournament (Stockfish binary required)

```
# With STOCKFISH_PATH environment variable set:
sbt demoStockfishTournament

# With explicit engine path + custom args (outputPath, repetitions, maxPly):
sbt "arenaDemoStockfish/run C:\path\to\stockfish.exe target/arena/stockfish-tournament/game-events.jsonl 1 200"
```

Argument order: `<enginePath> [outputPath] [repetitions] [maxPlyPerGame]`

If `STOCKFISH_PATH` is set in the environment, the first positional argument becomes
`outputPath` automatically (the engine path is read from the env var instead).

### Run Spark batch analytics

```
# Stockfish tournament data:
sbt demoStockfishSparkAnalytics

# Heuristic tournament data:
sbt demoSparkAnalytics

# Custom input/output:
sbt "sparkAnalytics/run target/arena/stockfish-tournament/game-events.jsonl target/spark-analytics-stockfish"
```

### Run tests

```
sbt testSparkAnalytics
```

---

## Repeated Round-Robin and Data Quality

With `repetitions = 1`, a 7-bot field produces 42 matchups (7 × 6). This gives a reasonable
data set for the basic leaderboard but thin numbers for per-configuration comparisons.

Using `repetitions = 2` or `3` produces 84–126 matchups and yields more stable win-rate
estimates, especially for Stockfish vs Stockfish pairings. Use the `repetitions` argument
to control this trade-off.

---

## Runtime Warning

**StockfishSlow adds significant wall-clock time.**

Each StockfishSlow move takes up to 1000 ms. With maxPly = 200 and 12 matchups involving
StockfishSlow (6 as white, 6 as black), worst-case runtime is around 20 minutes. In practice
games end earlier than maxPly, so typical runtime is 5–15 minutes for the full 42-matchup
tournament with repetitions = 1.

Recommendation:
- `repetitions = 1`, `maxPly = 200` for a quick demo (~5–15 minutes)
- `repetitions = 2`, `maxPly = 200` for stronger analytics data (~10–30 minutes)

---

## Analytics Tables

### Existing tables (Phase 6)

| Table | Description |
|-------|-------------|
| Leaderboard | Per-bot: totalScore, wins, draws, losses, gamesPlayed, avgPly, winRate |
| Head-to-Head | Per-pairing: whiteWins, blackWins, draws, whiteScore, blackScore |
| Termination Reasons | Counts by terminationReason (checkmate, stalemate, max-ply, …) |
| Avg Game Length | Per-pairing: avgTotalPly, avgDurationMs |

### New tables (Phase 7A)

| Table | Key columns |
|-------|-------------|
| Bot Family Comparison | family, games, wins, losses, draws, totalScore, winRate |
| Strategy Type Comparison | strategyType, games, wins, losses, draws, totalScore, winRate |
| White/Black Performance | botId, gamesAsWhite, whiteWins, whiteScore, gamesAsBlack, blackWins, blackScore |
| Fastest Winning Bots | winnerBotId, decisiveGames, avgWinPly, minWinPly, avgWinDurationMs |
| Stockfish Configuration Comparison | botId, strategyType, games, wins, draws, totalScore, avgGameLength, winRate |
| Family Matchups | whiteBotFamily, blackBotFamily, games, whiteScore, blackScore, draws |

### CSV output folders (when Hadoop native libs are available)

```
target/spark-analytics-stockfish/
  leaderboard/
  head-to-head/
  terminations/
  avg-game-length/
  bot-family-comparison/
  strategy-comparison/
  color-performance/
  fastest-wins/
  stockfish-comparison/
  family-matchups/
```

On Windows without `winutils.exe`, CSV writing is skipped with a warning; the console
tables above are the complete result.

---

## Architecture Rules Preserved

- `GameAnalytics` reads only normalized JSON fields — no UCI or domain classes imported.
- `StockfishFast` / `StockfishSlow` are configurations of the existing `StockfishBot` /
  `ProcessUciEngine` — no second UCI implementation.
- `arena-core` and heuristic bots have no dependency on Spark or Stockfish.
- `sparkAnalytics` module has no dependency on arena-bots-uci.

---

## Intentionally Not Implemented in Phase 7A

- SearchessAI / LCZero / Lichess import
- Web UI or database persistence
- Parallel game execution
- Schema registry / Avro / Protobuf
- Spark upgrade or new Kafka features
- Real Kafka broker smoke test (pending broker availability)
