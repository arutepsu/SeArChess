# SeArChess Web UI

## Setup

```bash
npm install
```

## Run


set CORS_ENABLED=true& set CORS_ALLOWED_ORIGIN=http://localhost:5173& sbt "bootstrapServer/runMain chess.server.ServerMain"
```bash
npm run dev
```

## Configuration

- `VITE_API_BASE_URL` (default: `http://localhost:8080`)
- `VITE_API_MOCK` set to `true` to use mock data
