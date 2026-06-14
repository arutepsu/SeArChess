# Spark Elo Analytics

Phase 9E adds batch-only Elo-style ratings for bots from Silver `GameFinished` events.
Phase 9F exposes those ratings through PostgreSQL, analytics-service, and the Web UI.

## Why Leaderboard Score Is Not Enough

The existing leaderboard is a direct tournament score table: wins, draws, losses, total score, games played, and average ply. That is still useful, but it treats every result equally regardless of opponent strength.

Elo-style ratings add an opponent-adjusted view. Beating a higher-rated bot is worth more rating movement than beating a lower-rated bot, and losing to a lower-rated bot costs more.

## Model

Defaults:

- Initial rating: `1000.0`
- K factor: `32.0`

Configuration:

- `ELO_INITIAL_RATING`
- `ELO_K_FACTOR`

Both values must be positive.

Scores:

- White win: white `1.0`, black `0.0`
- Black win: white `0.0`, black `1.0`
- Draw: both `0.5`

Expected score uses the standard Elo shape:

```text
expected = 1 / (1 + 10 ^ ((opponentRating - rating) / 400))
newRating = rating + K * (score - expected)
```

Games are processed in deterministic order:

```text
timestamp, then gameId
```

## Output

`EloAnalytics.computeRatings` returns:

- `botId`
- `rating`
- `ratingChange`
- `gamesPlayed`
- `wins`
- `draws`
- `losses`
- `averageOpponentRating`
- `lastGameTimestamp`

`GameAnalytics.run` prints:

```text
--- Elo Ratings ---
```

CSV output:

```text
elo-ratings
```

Optional Parquet lake output:

```text
gold/elo-ratings
```

PostgreSQL output when `POSTGRES_WRITE_ENABLED=true`:

```text
analytics_elo_ratings
```

The table is written through the shared `PostgresWriter`, so it receives the same metadata columns as the other analytics tables:

- `run_id`
- `source_path`
- `created_at`

analytics-service exposes the table through:

- `GET /api/analytics/latest/elo-ratings`
- `GET /api/analytics/runs/:runId/elo-ratings`

The Web UI `/analytics` page renders an **Elo Ratings** section with a rating chart and table.

## Driver-Side Iteration Tradeoff

Elo is order-sensitive and iterative, so it does not fit naturally into the same pure aggregation style as the existing Gold tables.

For the current tournament evaluation scale, Phase 9E collects ordered `GameFinished` rows to the Spark driver, computes deterministic ratings in Scala, and converts the result back to a DataFrame. This is simple, testable, and appropriate for local evaluation runs.

This approach is not suitable for unbounded massive streams. A later large-scale version could use stateful streaming, a state store, or a partition-aware design with explicit rating state management.

## Current Limitations

- Batch only.
- Per analytics run only.
- No streaming Elo.
- Existing batch CSV/PostgreSQL/Parquet outputs remain unchanged, with Elo added as a new CSV, optional Parquet Gold output, PostgreSQL table, API section, and UI section.
