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
`http://localhost:5173`, and the browser should call Game only through Envoy at
`http://localhost:10000`.

## Configuration

- `VITE_API_BASE_URL` (default: `http://localhost:10000`)
- `VITE_WS_URL` (default: `ws://localhost:10000/ws`; games connect at `/games/{gameId}`)
- `VITE_API_MOCK` set to `true` to use mock data

The browser talks only to Envoy. HTTP commands and reads remain authoritative;
WebSocket messages are refresh signals for the current game.
