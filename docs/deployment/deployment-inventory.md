# Deployment Inventory

Canonical compose file: `deployment/compose/docker-compose.yml`
Dev overlay: `deployment/compose/docker-compose.dev-ports.yml`

---

## Networks

| Network | Services | Purpose |
|---------|----------|---------|
| `edge` | envoy, game-service | Public-facing traffic (Envoy → game-service HTTP/WS) |
| `internal` | game-service, history-service, ai-service, postgres, mongo, redis | Service mesh and data stores; no host exposure |
| `observability` | game-service, history-service, ai-service, prometheus, grafana | Prometheus scrape lane |

Data stores (postgres, mongo, redis) are on `internal` only — they have no path to `edge` or `observability`.

---

## Services

| Service | Image / Build | Networks | Internal port(s) | Host port(s) | Dependencies | Healthcheck |
|---------|--------------|----------|-----------------|--------------|--------------|-------------|
| **envoy** | `envoyproxy/envoy:v1.32-latest` | edge | 10000 | **10000** | game-service healthy | `GET 127.0.0.1:9901/ready` |
| **game-service** | `Dockerfile` (sbt stage) | edge, internal, observability | 8080, 9090 | — | postgres, mongo, redis, ai-service, history-service | `GET 127.0.0.1:8080/health` |
| **history-service** | `Dockerfile.history` (sbt stage) | internal, observability | 8081 | — | postgres healthy | `GET 127.0.0.1:8081/health` |
| **ai-service** | `Dockerfile.ai` (sbt stage) | internal, observability | 8765 | — | — | `GET 127.0.0.1:8765/health` |
| **postgres** | `postgres:16` | internal | 5432 | — ¹ | — | `pg_isready -U searchess -d searchess` |
| **mongo** | `mongo:7.0` | internal | 27017 | — ¹ | — | Compose: `mongosh --eval "db.adminCommand('ping').ok"` / k3d: `tcpSocket :27017` ² |
| **redis** | `redis:7.4-alpine` | internal | 6379 | — ¹ | — | `redis-cli ping` |
| **prometheus** | `prom/prometheus:v2.55.1` | observability | 9090 | — ¹ | — | `GET 127.0.0.1:9090/-/healthy` |
| **grafana** | `grafana/grafana:11.4.0` | observability | 3000 | **3000** (Compose) / **3001** (k3d) | prometheus healthy | `GET 127.0.0.1:3000/api/health` |

¹ Exposed to the host only when using the dev overlay (Compose) or the k3d port mapping.

² The k3d Kubernetes StatefulSet uses `tcpSocket :27017` instead of the exec probe. The `mongosh` command spawns a Node.js process that frequently exceeds the 1 s probe timeout under k3d/Docker Desktop even when MongoDB is healthy. A TCP connection check is sufficient to confirm the listener is up.

> **Grafana host port by runtime:**
> - Docker Compose (`deployment/compose/docker-compose.yml`): `localhost:3000`
> - k3d local Kubernetes (`deployment/k3d/cluster.yaml`): `localhost:3001`
>
> The in-cluster Grafana Service and container always use port 3000.
> The k3d load-balancer maps `host:3001 → cluster:3000` to avoid conflicting with
> a Compose Grafana that may already be running on the same developer machine.

---

## Dev overlay host ports

`deployment/compose/docker-compose.dev-ports.yml` adds the following host port bindings:

| Port | Service |
|------|---------|
| 5432 | postgres |
| 27017 | mongo |
| 6379 | redis |
| 9090 | prometheus |

Usage:
```bash
docker compose -f deployment/compose/docker-compose.yml \
               -f deployment/compose/docker-compose.dev-ports.yml up -d
```

---

## Envoy routing

All public traffic enters on `:10000`. Routes (priority order):

| Match | Upstream | Notes |
|-------|---------|-------|
| `GET /health` | game-service:8080 | Liveness probe passthrough |
| `prefix /api/` | game-service:8080 | REST API (prefix rewritten to `/`) |
| `prefix /ws/` | game-service:9090 | WebSocket (upgrade enabled) |
| `GET /admin/migrations` | game-service:8080 | Guarded by `MIGRATION_ADMIN_TOKEN` |
| everything else | — | 404 direct response |

History, AI, and data-store services are **not** routable through Envoy.

---

## Prometheus scrape targets

Config: `deployment/compose/prometheus/prometheus.yml`

| Job | Target | Scrape path |
|-----|--------|-------------|
| searchess-game-service | `game-service:8080` | `/metrics` |
| searchess-history-service | `history-service:8081` | `/metrics` |
| searchess-ai-service | `ai-service:8765` | `/metrics` |

Prometheus is on the `observability` network; all three app services are also on `observability`, so scraping resolves by Docker DNS without touching `internal` or `edge`.

Grafana dashboards (pre-provisioned from `tools/performance/observability/grafana/`):
`searchess-domain`, `searchess-http`, `searchess-jvm`.

---

## Volume inventory

| Volume name | Used by | Contents |
|-------------|---------|---------|
| `searchess_postgres_data` | postgres | Postgres data directory; game-service uses schema `game`, history-service uses schema `history` |
| `searchess_mongo_data` | mongo | MongoDB data files |
| `searchess_redis_data` | redis | Redis RDB/AOF persistence |
| `searchess_prometheus_data` | prometheus | TSDB (7-day retention) |
| `searchess_grafana_data` | grafana | Grafana state |

---

## Environment variables

All variables documented in `.env.example` at the repo root.

| Variable | Default | Notes |
|----------|---------|-------|
| `SEARCHESS_POSTGRES_PASSWORD` | `searchess` | **Change before any shared deployment** |
| `SEARCHESS_POSTGRES_USER` | `searchess` | Must match `POSTGRES_USER` in postgres service |
| `SEARCHESS_POSTGRES_SCHEMA` | `game` | Dedicated game-service schema for Flyway and Slick runtime persistence |
| `PERSISTENCE_MODE` | `postgres` | `postgres \| mongo \| sqlite \| in-memory` |
| `CORS_ALLOWED_ORIGIN` | `http://localhost:5173` | Frontend origin |
| `AI_ENGINE_ID` | `random-legal` | AI move provider |
| `AI_REMOTE_TEST_MODE` | *(empty)* | Leave blank for normal operation |
| `MIGRATION_ADMIN_ENABLED` | `false` | Enable schema migration endpoint |
| `MIGRATION_ADMIN_TOKEN` | *(empty)* | Required when migration endpoint is on |
| `HISTORY_POSTGRES_URL` | — | **Required** for history-service; JDBC URL to Postgres |
| `HISTORY_POSTGRES_SCHEMA` | `history` | Dedicated history-service schema; keeps Flyway/Slick isolated from game-service schema |
| `HISTORY_DELIVERY_MODE` | `redis-stream` | game-service delivery mode: `redis-stream \| http` |
| `HISTORY_INGESTION_MODE` | `redis-stream` | history-service ingestion mode: `redis-stream \| http` |
| `HISTORY_REDIS_URL` | `redis://redis:6379` | Redis endpoint for async archive delivery |
| `HISTORY_REDIS_STREAM` | `searchess.history.archives` | Redis Stream carrying archive request envelopes |
| `HISTORY_REDIS_GROUP` | `history-service` | history-service consumer group |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | **Change before sharing** |

---

## How to start

```bash
# 1. Seed secrets
cp .env.example .env
# Edit SEARCHESS_POSTGRES_PASSWORD and GRAFANA_ADMIN_PASSWORD at minimum

# 2. Production-like stack (no DB / Prometheus host ports)
docker compose -f deployment/compose/docker-compose.yml up -d --build

# 3. Dev stack (DB + Prometheus ports open on host)
docker compose -f deployment/compose/docker-compose.yml \
               -f deployment/compose/docker-compose.dev-ports.yml up -d --build
```

---

## How to verify health

```bash
# All containers should report "healthy" (allow ~2 min for JVM warm-up)
docker compose -f deployment/compose/docker-compose.yml ps

# Game service via Envoy edge
curl http://localhost:10000/health

# Grafana health endpoint (Docker Compose)
curl http://localhost:3000/api/health

# Grafana health endpoint (k3d)
curl http://localhost:3001/api/health

# Prometheus targets (dev overlay only — requires 9090 open on host)
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[].health'

# Redis (dev overlay only)
redis-cli -h 127.0.0.1 -p 6379 ping

# Postgres (dev overlay only)
psql -h 127.0.0.1 -U searchess -d searchess -c '\conninfo'
```

---

## Config validation

Run from the repo root to verify without starting containers:
```bash
# Canonical file
docker compose -f deployment/compose/docker-compose.yml config

# With dev overlay
docker compose -f deployment/compose/docker-compose.yml \
               -f deployment/compose/docker-compose.dev-ports.yml config
```

Both exit 0 as of last validation (2026-05-19).

---

## Readiness for k3d / Kubernetes

| Prerequisite | Status |
|-------------|--------|
| All services containerised with Dockerfiles | ✅ |
| Explicit named networks (edge / internal / observability) | ✅ |
| No data-store host ports in canonical file | ✅ |
| Healthchecks on all services | ✅ |
| `.env.example` with all required variables | ✅ |
| Service names match inter-service URLs | ✅ |
| TLS at Envoy edge | ❌ needs TLS listener or upstream LB |
| Redis auth configured | ❌ add `requirepass` before exposing Redis |
| history-service Postgres backend | ✅ Slick + Flyway; table `history.history_archives` in shared Postgres; SQLite removed |
| Envoy admin port scraping by Prometheus | ⚠️ admin on 127.0.0.1:9901, not reachable cross-container |
| Keycloak / auth layer | ❌ deferred |
| Redis Streams archive delivery | ✅ game-service publishes `history.archive.requested`; history-service consumes via consumer group |

---

## Known risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Postgres password defaults to `searchess` | High | Set `SEARCHESS_POSTGRES_PASSWORD` in `.env` |
| Redis has no auth | Medium | Add `command: redis-server --requirepass ...` |
| history-service single replica | Low | Postgres backend enables scaling; increase replicas after load validation |
| No TLS at Envoy | Medium | Add TLS listener or LB termination |
| Envoy admin not Prometheus-reachable | Low | Publish `:9901` or proxy if Envoy stats are needed |
