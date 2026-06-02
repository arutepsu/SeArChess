# SeArChess Web UI

## Setup

```bash
npm install
```

## Run

```bash
npm run dev
```

For the canonical local topology, run the backend stack from the repo root:

```bash
docker compose up --build
```

The Compose Game Service enables CORS for the Vite dev origin
`http://localhost:5173`, and local development can call Game through Envoy at
`http://localhost:10000` either by explicit API base or through the Vite proxy.

## Configuration

- `VITE_API_BASE_URL` (default: empty; deployed builds use same-origin relative API URLs)
- `VITE_API_PATH_PREFIX` (default: empty; set to `/api` only when the backend is mounted under that prefix)
- `VITE_DEV_PROXY_TARGET` (dev server only; set to the tunneled backend target for same-origin local testing)
- `VITE_WS_URL` (default: same-origin `/ws`; games connect at `/games/{gameId}`)
- `VITE_LICHESS_BOT_WS_URL` (optional; use `ws://localhost:9323` only for local bot live-monitor debugging)
- `VITE_API_MOCK` set to `true` to use mock data

The deployed Web UI image is served by Envoy and must use same-origin URLs:

- `VITE_API_BASE_URL=`
- `VITE_API_PATH_PREFIX=`
- `VITE_WS_URL=`

With those values, browser requests are relative paths such as `/sessions`,
`/health`, `/games/{gameId}/ai-move`, and `/ws/games/{gameId}`. Envoy routes
them internally, so no browser CORS exception is needed.

For SSH tunnel testing against the deployed backend, run Vite with relative API URLs
and the dev proxy. In PowerShell:

```powershell
Remove-Item Env:\VITE_API_BASE_URL -ErrorAction SilentlyContinue
Remove-Item Env:\VITE_API_PATH_PREFIX -ErrorAction SilentlyContinue
$env:VITE_DEV_PROXY_TARGET="http://127.0.0.1:10000"
$env:VITE_LICHESS_BOT_WS_URL=""
npm run dev:deployed -- --host 127.0.0.1 --port 5173
```

The browser talks only to Envoy. HTTP commands and reads remain authoritative;
WebSocket messages are refresh signals for the current game.
