# Local Envoy Edge

Status: local/dev edge entrypoint  
Scope: plain Envoy reverse proxy with static Docker Compose routing

## Topology

```text
Client
  -> Envoy :10000
      -> Game Service HTTP :8080
      -> Game Service WebSocket :9090

Game Service
  -> History Service :8081
  -> AI Service :8765
```

Envoy is the only normal host-published entrypoint in Docker Compose. It is an
edge reverse proxy, not a service mesh.

## Public Through Envoy

| Public path | Backend | Notes |
|---|---|---|
| `GET /health` | `game-service:8080/health` | Game Service liveness through the edge. |
| `/api/*` | `game-service:8080/*` | `/api` prefix is stripped before forwarding. |
| `/ws/*` | `game-service:9090/ws/*` | WebSocket upgrade enabled for Game live updates. |

Examples:

- `POST /api/sessions` -> `POST /sessions`
- `GET /api/games/{gameId}` -> `GET /games/{gameId}`
- `GET /api/archive/games/{gameId}` -> `GET /archive/games/{gameId}`
- `ws://127.0.0.1:10000/ws/games/{gameId}` -> Game WebSocket server

Unknown paths return a simple Envoy `404`.

## Internal Only

These services are intentionally not published to the host by Compose:

- `game-service:8080`
- `game-service:9090`
- `history-service:8081`
- `ai-service:8765`

East-west calls stay direct on the Compose network:

- Game -> History uses `http://history-service:8081`
- Game -> AI uses `http://ai-service:8765`
- History -> Game archive reads use `http://game-service:8080`

## Non-Goals

This slice does not add auth, rate limiting, retries, mTLS, dynamic discovery,
Consul, Kubernetes, Envoy Gateway, or service mesh behavior.
