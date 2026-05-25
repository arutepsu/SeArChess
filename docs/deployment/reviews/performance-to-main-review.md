# Performance → Main Review

**Status:** Draft  
**Branch:** performance → main  
**Purpose:** Classify the migration diff before merging the temporary `performance` branch into `main`.

---

## 1. Summary

This PR is not a small deployment-only change. It migrates the validated platform, GitOps, and university-server deployment work from the temporary `performance` branch into `main`.

The changes include:

- k3d/k3s university server deployment
- Kustomize base and overlays
- GHCR registry deployment
- GitHub Actions image build workflow
- Argo CD AppProject and Application
- Python AI service Kubernetes workload
- Scala ai-service proxy support for Python AI
- Redis Streams history delivery
- History service Postgres/Slick persistence
- Game/history Postgres schema isolation
- Prometheus/Grafana deployment
- StatefulSet drift fixes for Argo CD
- Release and GitOps strategy document
- Deployment evidence

---

## 2. Safe / expected deployment changes

These files are expected platform/deployment additions or documentation changes.

### CI / registry

- `.github/workflows/build-images.yml`
- `.env.example`
- `.gitignore`

### Docker / Compose

- `deployment/compose/docker-compose.yml`
- `deployment/compose/docker-compose.dev-ports.yml`
- `deployment/compose/prometheus/prometheus.yml`
- `docker-compose.yml`
- `Dockerfile.history`

### k3d / server scripts

- `deployment/k3d/cluster.yaml`
- `deployment/k3d/server-cluster.yaml`
- `deployment/server/deploy-server-argocd.sh`
- `deployment/server/deploy-server-k3d.sh`
- `deployment/server/deploy-server-registry.sh`
- `deployment/server/import-images.sh`
- `deployment/server/verify-server-k3d.sh`
- `deployment/server/verify-server-registry.sh`

### Argo CD

- `deployment/argocd/README.md`
- `deployment/argocd/install-argocd.sh`
- `deployment/argocd/port-forward-argocd.sh`
- `deployment/argocd/searchess-application.yaml`
- `deployment/argocd/searchess-project.yaml`

### Kubernetes base and overlays

- `deployment/k8s/base/**`
- `deployment/k8s/overlays/local-k3d/**`
- `deployment/k8s/overlays/uni-server-k3d/**`
- `deployment/k8s/overlays/uni-server-registry/**`

### Observability

- `deployment/k8s/base/prometheus/**`
- `deployment/k8s/base/grafana/**`
- `deployment/compose/prometheus/prometheus.yml`

### Web UI deployed-backend mode

- `apps/web-ui/.env.deployed`
- `apps/web-ui/package.json`
- `apps/web-ui/src/vite-env.d.ts`

### Deployment documentation and evidence

- `docs/deployment/**`
- `docs/architecture/python-ai-service-integration.md`
- `docs/architecture/redis-history-delivery.md`
- `docs/architecture/game-history-outbox.md`
- `docs/contracts/history-service-http-v1.md`
- `docs/persistence-demo.md`
- `docs/dev-guide-history-service.md`

---

## 3. Expected application/platform changes

These are not pure deployment changes, but they are expected because they implement the validated platform behavior.

### Scala ai-service → Python AI proxy

- `apps/ai-service/src/main/scala/chess/aiservice/AiServiceConfig.scala`
- `apps/ai-service/src/main/scala/chess/aiservice/AiServiceRoutes.scala`
- `apps/game-service/modules/ai/src/test/scala/chess/adapter/ai/remote/RemoteAiIntegrationSpec.scala`

Expected behavior:
- ai-service can proxy to `python-ai-service`
- local fallback remains available
- game-service contract remains unchanged

### Redis Streams history delivery

- `apps/game-service/modules/history-delivery/**`
- `modules/game-event-contract/src/main/scala/chess/adapter/event/HistoryArchiveStreamEvent.scala`
- `modules/game-event-contract/src/test/scala/chess/adapter/event/HistoryArchiveStreamEventSpec.scala`

Expected behavior:
- game-service publishes history archive events
- history-service consumes them through Redis Streams
- duplicate events remain idempotent

### Game-service Postgres schema isolation

- `apps/game-service/modules/persistence/src/main/scala/chess/adapter/repository/postgres/**`
- `apps/game-service/modules/persistence/src/main/scala/chess/adapter/repository/slick/SlickTables.scala`
- `apps/game-service/src/main/scala/chess/server/config/**`
- `apps/game-service/src/main/scala/chess/server/migration/**`
- `apps/game-service/src/main/scala/chess/server/assembly/PersistenceAssembly.scala`
- `apps/game-service/src/main/scala/chess/server/GameServiceMain.scala`

Expected behavior:
- game-service uses dedicated Postgres schema
- Flyway and Slick use the same configured schema
- no baselineOnMigrate workaround is introduced

### History-service Postgres/Slick persistence

- `apps/history-service/modules/core/src/main/resources/db/migration/history/V1__create_history_archives.sql`
- `apps/history-service/modules/core/src/main/scala/chess/history/postgres/HistoryFlywaySchemaInitializer.scala`
- `apps/history-service/modules/core/src/main/scala/chess/history/slick/SlickPostgresArchiveRepository.scala`
- `apps/history-service/modules/core/src/main/scala/chess/history/redis/RedisStreamHistoryConsumer.scala`
- `apps/history-service/src/main/scala/chess/historyservice/**`

Expected behavior:
- history-service stores archives in Postgres
- history-service uses dedicated `history` schema
- Redis ingestion mode is supported
- SQLite is no longer the active Kubernetes persistence path

### Build definition

- `build.sbt`

Expected behavior:
- new module dependencies for Redis Streams, Postgres/Flyway/Slick, and tests are wired correctly

---

## 4. Review-critical changes

These files must be reviewed carefully before merge.

### SQLite removal

- `apps/history-service/modules/core/src/main/scala/chess/history/sqlite/SqliteArchiveRepository.scala`
- `apps/history-service/modules/core/src/test/scala/chess/history/sqlite/SqliteArchiveRepositorySpec.scala`

Review question:
- Is removing SQLite acceptable for this branch, or should SQLite remain as a local/dev option?

### Event delivery behavior

- `apps/game-service/src/main/scala/chess/server/assembly/EventAssembly.scala`
- `apps/game-service/modules/history-delivery/src/main/scala/chess/adapter/event/RedisStreamHistoryPublisher.scala`

Review question:
- Does Redis Streams mode remain configurable and safe?
- Does HTTP delivery/fallback behavior remain documented?

### ai-service runtime behavior

- `apps/ai-service/src/main/scala/chess/aiservice/AiServiceRoutes.scala`

Review question:
- Does Python proxy fallback behave safely on timeout, HTTP error, and connection failure?

### Compose consistency

- `docker-compose.yml`
- `deployment/compose/docker-compose.yml`

Review question:
- Are there now two compose definitions with conflicting behavior?
- Which one is canonical after merge?

### build.sbt

- `build.sbt`

Review question:
- Do the new dependencies affect unrelated modules or test classpaths?

---

## 5. Validation expected before merge

Run locally or in CI:

```sh
sbt "gameEventContract/test" "gameHistoryDelivery/test" "history/test" "gameService/test" "aiService/test"

