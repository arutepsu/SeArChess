# Web UI Analytics Page — Phase 8C

## Purpose

The `/analytics` route in `apps/web-ui` displays bot evaluation tournament results
fetched from `analytics-service`. It reads no JSONL, runs no Spark, and connects to
no database directly — it consumes only the `analytics-service` HTTP API.

---

## Required Backend Services

| Service             | Default port | Purpose                                     |
|---------------------|-------------|---------------------------------------------|
| `analytics-service` | **8084**    | HTTP read API over PostgreSQL analytics tables |
| PostgreSQL          | 5432        | Stores analytics tables written by Spark    |

`analytics-service` must be running and connected to a PostgreSQL instance that already
has analytics tables populated by `spark-analytics`.

---

## Environment Variables

| Var                          | Required | Default     | Description                                                 |
|------------------------------|----------|-------------|-------------------------------------------------------------|
| `VITE_ANALYTICS_PROXY_TARGET` | dev only | —           | Dev-mode proxy target for `/api/analytics/*` → analytics-service |
| `VITE_ANALYTICS_API_BASE_URL` | no       | `""` (same origin) | Full base URL for analytics API calls in production |

### Dev mode (`.env.analytics`)

```
VITE_AUTH_ENABLED=false
VITE_ANALYTICS_API_BASE_URL=
VITE_ANALYTICS_PROXY_TARGET=http://localhost:8084
```

`VITE_ANALYTICS_API_BASE_URL` must be **empty** in dev mode. When empty,
`analyticsClient.ts` uses relative URLs (`/api/analytics/...`) which Vite
intercepts and forwards to `http://localhost:8084`.

`VITE_ANALYTICS_PROXY_TARGET` tells `vite.config.ts` to register a
`/api/analytics` proxy entry (before the general `/api` entry so it takes
precedence) with `changeOrigin: true`. The browser never makes a cross-origin
request — it talks only to the Vite dev server on `localhost:5173`.

**Why not set `VITE_ANALYTICS_API_BASE_URL=http://localhost:8084` directly?**

Setting a full cross-origin URL makes the browser call `analytics-service`
directly. Because `analytics-service` does not send `Access-Control-Allow-Origin`
headers, the browser blocks the request with a CORS error:

```
Access to fetch at 'http://localhost:8084/api/analytics/...'
from origin 'http://localhost:5173' has been blocked by CORS policy
```

Leaving the base URL empty and routing through the Vite proxy avoids this
entirely — no CORS headers needed on `analytics-service` for local dev.

### Production

In production (Envoy/nginx), route `/api/analytics/*` to `analytics-service:8084`.
Leave `VITE_ANALYTICS_API_BASE_URL` unset (empty = same origin).

---

## Local Run Sequence

1. **Run evaluation tournament** to produce JSONL game events:
   ```bash
   sbt demoEvaluationTournament
   # output: target/arena/evaluation-tournament/game-events.jsonl
   ```

2. **Run Spark analytics** with PostgreSQL write enabled:
   ```bash
   export POSTGRES_WRITE_ENABLED=true
   export POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
   export POSTGRES_USER=searchess
   export POSTGRES_PASSWORD=secret
   export POSTGRES_SCHEMA=analytics
   sbt demoEvaluationSparkAnalytics
   ```

3. **Start analytics-service**:
   ```bash
   export ANALYTICS_POSTGRES_URL=jdbc:postgresql://localhost:5432/searchess
   export ANALYTICS_POSTGRES_USER=searchess
   export ANALYTICS_POSTGRES_PASSWORD=secret
   export ANALYTICS_POSTGRES_SCHEMA=analytics
   sbt analyticsService/run
   # listening on :8084
   ```

4. **Start web UI in analytics mode**:
   ```bash
   cd apps/web-ui
   npm run dev:analytics
   # Vite dev server on :5173, proxying /api/analytics → :8084
   # VITE_AUTH_ENABLED=false, no Keycloak needed
   ```

5. **Open analytics page**:
   ```
   http://localhost:5173/analytics
   ```
   Or click **Analytics** in the top-right navigation bar.

---

## Endpoint Mapping

| Page section                  | API endpoint                                   | analytics-service source table         |
|-------------------------------|------------------------------------------------|----------------------------------------|
| Latest run metadata           | `GET /api/analytics/runs`                      | `analytics_leaderboard` (DISTINCT)     |
| Leaderboard                   | `GET /api/analytics/latest/leaderboard`        | `analytics_leaderboard`                |
| Bot Family Comparison         | `GET /api/analytics/latest/bot-families`       | `analytics_bot_family_comparison`      |
| Strategy Comparison           | `GET /api/analytics/latest/strategies`         | `analytics_strategy_comparison`        |
| Searchess AI vs Opponents     | `GET /api/analytics/latest/searchess-ai`       | `analytics_searchess_ai_comparison`    |
| vs Stockfish Variants         | `GET /api/analytics/latest/stockfish`          | `analytics_stockfish_comparison`       |
| Average Game Length by Pairing | `GET /api/analytics/latest/avg-game-length`   | `analytics_avg_game_length`            |
| Elo Ratings                   | `GET /api/analytics/latest/elo-ratings`        | `analytics_elo_ratings`                |
| Termination Reasons           | `GET /api/analytics/latest/terminations`       | `analytics_terminations`               |
| Fastest Winning Bots          | `GET /api/analytics/latest/fastest-wins`       | `analytics_fastest_wins`               |
| Color Performance             | `GET /api/analytics/latest/color-performance`  | `analytics_color_performance`          |

When a historical run is selected, section requests use `GET /api/analytics/runs/:runId/<section>`.

All 11 requests are issued in parallel via `Promise.allSettled` on page mount or run selection change.
Each section renders independently — a failure in one table does not block others.

---

## Files Added / Modified

| File | Change |
|------|--------|
| `src/api/analyticsTypes.ts` | New — TypeScript types for all analytics API responses |
| `src/api/analyticsClient.ts` | New — fetch functions for all analytics endpoints |
| `src/features/analytics/pages/AnalyticsPage.tsx` | Analytics page component with run selector and data sections |
| `src/features/analytics/components/charts/EloRatingsChart.tsx` | Elo ratings horizontal bar chart |
| `src/features/analytics/components/charts/TerminationsChart.tsx` | Termination reason horizontal bar chart |
| `src/features/analytics/components/charts/FastestWinsChart.tsx` | Fastest wins horizontal bar chart |
| `src/features/analytics/components/charts/ColorPerformanceChart.tsx` | Color performance chart |
| `src/components/AnalyticsPage.css` | New — dark glass-morphism styles matching project theme |
| `src/App.tsx` | Added `<Route path="/analytics" element={<AnalyticsPage />} />` |
| `src/components/AuthBar.tsx` | Added Analytics nav button |
| `src/vite-env.d.ts` | Added `VITE_ANALYTICS_API_BASE_URL`, `VITE_ANALYTICS_PROXY_TARGET` |
| `vite.config.ts` | Added `/api/analytics` proxy (before `/api` for precedence) |
| `.env.analytics` | Analytics dev mode: `VITE_AUTH_ENABLED=false`, empty `VITE_ANALYTICS_API_BASE_URL`, `VITE_ANALYTICS_PROXY_TARGET=http://localhost:8084` |

---

## UI Behaviour

- All sections show a **loading** state while data is fetching.
- On success with data: table is shown.
- On success with empty rows: "No data available." message.
- On API error (including 503 from analytics-service): human-readable message:
  > "Analytics data unavailable. Run Spark analytics with PostgreSQL output enabled first."
- Run metadata (run ID, timestamp, source path) is shown in the page header when available.
- The run selector reloads every section, including Elo ratings, for the selected run.
- The Elo Ratings section shows a rating chart and a table with rating, rating change, record, games played, and average opponent rating.
- Termination Reasons, Fastest Winning Bots, and Color Performance expose the existing Spark Gold tables through the same chart and table pattern.

---

## Architecture Boundaries

```
Web UI (/analytics)
  └── analyticsClient.ts  ──GET──►  analytics-service :8084
                                          │
                                    (reads only)
                                          │
                                    PostgreSQL analytics.*
                                          │
                                    (written by)
                                          │
                                    spark-analytics (offline batch)
```

- Web UI does **not** connect to PostgreSQL directly.
- Web UI does **not** read JSONL.
- Web UI does **not** run Spark.
- `analytics-service` does not depend on game-service, arena, or bot modules.

---

## Limitations

- No auto-refresh: data is fetched on page load and when the selected run changes. Reload the page to pick up new Spark runs.
- No authentication on analytics-service: the API is unprotected (suitable for internal/local use).
