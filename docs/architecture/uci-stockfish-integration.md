# UCI Stockfish Integration

## Purpose

Phase 5A adds optional Stockfish bot profiles to the Bot Evaluation Arena through a dedicated UCI adapter module:

```
arenaBotsUci --> arenaCore --> arenaEvents
      |
      +--> notation (FEN export)
```

The rest of the arena still sees only `BotPlayer`. `GameRunner`, `TournamentRunner`, heuristic bots, and Spark analytics do not depend on UCI classes.

## Adapter Design

`arenaBotsUci` owns all UCI process communication:

- starts the configured engine process
- sends `uci` and waits for `uciok`
- sends `isready` and waits for `readyok`
- exports the current `GameState` to FEN via `FenNotationFacade`
- sends `position fen <fen>`
- sends `go depth N` or `go movetime N`
- parses `bestmove ...`
- maps the UCI move back to one of `GameStateRules.legalMoves(state)`
- closes the process with `quit`
- applies startup and move response timeouts

`GameRunner` remains the legality authority. The UCI bot returns a domain `Move`; `GameRunner` still applies and validates it.

## Installing Stockfish

Stockfish is a command-line UCI chess engine available for Windows, macOS, and Linux.
Use the official download page when you want a known release binary:

- https://stockfishchess.org/download/
- https://github.com/official-stockfish/Stockfish/releases

Typical options:

- Windows: download the Windows archive from the official Stockfish download page, extract it, and point `STOCKFISH_PATH` at the `.exe`.
- macOS: download a macOS build from the official page/GitHub releases, or install with Homebrew if available in your environment.
- Linux: install from your distribution package manager when available, or download a Linux release from the official page/GitHub releases.

The arena does not bundle Stockfish. CI and local tests work without it; real-engine checks run only when a valid executable path is provided.

## Stockfish Path

The Stockfish binary is not hardcoded. Provide it either as:

```
STOCKFISH_PATH=/path/to/stockfish
```

or as the first argument to the demo:

```
sbt "arenaDemoStockfish/run /path/to/stockfish"
```

On Windows, use the `.exe` path, for example:

```
$env:STOCKFISH_PATH="C:\tools\stockfish\stockfish.exe"
```

On Linux/macOS:

```
export STOCKFISH_PATH=/usr/local/bin/stockfish
```

If Stockfish is on `PATH`, first resolve its absolute path:

```
# Linux/macOS
export STOCKFISH_PATH="$(command -v stockfish)"

# Windows PowerShell
$env:STOCKFISH_PATH=(Get-Command stockfish).Source
```

## Bot Profiles

Implemented profiles:

- `stockfish-depth-1`, strategy `depth-1`
- `stockfish-depth-3`, strategy `depth-3`
- optional helpers `stockfish-fast` and `stockfish-slow` using `movetime`

All Stockfish profiles use:

- `family = BotFamily.UciEngine`
- `engineType = stockfish`
- `modelVersion = none`

## Smoke Check

The smoke check starts the configured engine through the UCI adapter, asks for one move from the initial position, prints that move, and exits. It is optional and is not required in CI.

```
sbt stockfishSmokeCheck
```

Equivalent explicit command:

```
sbt "arenaDemoStockfish/runMain chess.arena.demo.StockfishSmokeCheck"
```

You can also pass the engine path directly:

```
sbt "arenaDemoStockfish/runMain chess.arena.demo.StockfishSmokeCheck /path/to/stockfish"
```

## Run The Tournament Demo

Default output:

```
target/arena/stockfish-tournament/game-events.jsonl
```

Command:

```
sbt demoStockfishTournament
```

Optional arguments:

```
sbt "arenaDemoStockfish/run <stockfishPath> [outputPath] [repetitions] [maxPlyPerGame]"
```

The output path is treated as trusted local input. The default output path is under `target/`. If you pass a custom output path, do not point it at an important file. The demo refuses to delete directories or non-regular files and only replaces an existing regular file.

The demo tournament includes:

- `random-bot`
- `capture-first`
- `material-greedy`
- `stockfish-depth-1`
- `stockfish-depth-3`

## Spark Analytics

Run analytics against the generated Stockfish tournament JSONL:

```
sbt demoStockfishSparkAnalytics
```

Equivalent explicit command:

```
sbt "sparkAnalytics/run target/arena/stockfish-tournament/game-events.jsonl target/spark-analytics-stockfish"
```

The older alias `demoSparkAnalyticsStockfish` is also available and points at the same command.

Spark analytics reads raw JSONL only and has no dependency on UCI code.

Default Spark output directory:

```
target/spark-analytics-stockfish
```

## Tests

Unit tests cover:

- normal `bestmove` parsing
- `bestmove` with `ponder`
- promotion moves such as `e7e8q`
- malformed bestmove rejection
- stable Stockfish bot metadata

Integration tests are optional. If `STOCKFISH_PATH` is set to an executable file, they verify:

- `StockfishDepth1` selects a legal move from the initial position
- `StockfishDepth3` selects a legal move from the initial position
- `GameRunner` can run `RandomBot` vs `StockfishDepth1`
- JSONL includes `GameStarted`, `MovePlayed`, and `GameFinished`

If `STOCKFISH_PATH` is missing or not executable, these tests are marked pending with a clear message.

Run the UCI tests without Stockfish:

```
sbt testArenaBotsUci
```

Run real Stockfish integration tests:

```
# Windows PowerShell
$env:STOCKFISH_PATH="C:\tools\stockfish\stockfish.exe"
sbt testArenaBotsUci

# Linux/macOS
export STOCKFISH_PATH=/usr/local/bin/stockfish
sbt testArenaBotsUci
```

## Full Local Pipeline

With `STOCKFISH_PATH` set:

```
sbt testArenaBotsUci
sbt stockfishSmokeCheck
sbt demoStockfishTournament
sbt demoStockfishSparkAnalytics
```

Or with the explicit analytics command:

```
sbt "sparkAnalytics/run target/arena/stockfish-tournament/game-events.jsonl target/spark-analytics-stockfish"
```

Generated JSONL:

```
target/arena/stockfish-tournament/game-events.jsonl
```

Generated analytics report directory:

```
target/spark-analytics-stockfish
```

## Out Of Scope

This phase does not add LCZero, SearchessAI adapters, Lichess import, Kafka, Spark Structured Streaming, database persistence, dashboard/UI work, Spark upgrades, or parallel game execution. LCZero remains future work.
