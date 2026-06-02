# External Game Lichess Bot Deployment

This runbook wires the already implemented external-game REST API into Docker
Compose and Kubernetes. It does not change game logic.

## Runtime Environment

Game Service:

```text
EXTERNAL_GAME_BOT_API_KEY=<shared secret>
EXTERNAL_GAME_BOT_PLATFORM=Lichess
EXTERNAL_GAME_BOT_ACTOR_ID=selinasa
AI_PROVIDER_MODE=remote
PERSISTENCE_MODE=postgres
SEARCHESS_POSTGRES_URL=jdbc:postgresql://postgres:5432/searchess
SEARCHESS_POSTGRES_USER=searchess
SEARCHESS_POSTGRES_PASSWORD=<secret>
SEARCHESS_POSTGRES_SCHEMA=game
```

Lichess Bot:

```text
LICHESS_BOT_TOKEN=<lichess oauth token>
GAME_SERVICE_BASE_URL=http://game-service:8080
EXTERNAL_GAME_BOT_API_KEY=<same shared secret as Game Service>
EXTERNAL_GAME_BOT_PLATFORM=Lichess
EXTERNAL_GAME_BOT_ACTOR_ID=selinasa
```

Never commit real values for `EXTERNAL_GAME_BOT_API_KEY` or `LICHESS_BOT_TOKEN`.

## Local Compose

Copy `.env.example` to `.env`, then fill only local values:

```bash
EXTERNAL_GAME_BOT_API_KEY=<strong local shared key>
EXTERNAL_GAME_BOT_PLATFORM=Lichess
EXTERNAL_GAME_BOT_ACTOR_ID=selinasa
LICHESS_BOT_TOKEN=<lichess bot token>
```

Start the production-like local stack without the bot:

```bash
docker compose -f deployment/compose/docker-compose.yml up -d
```

Start the bot only for a controlled Lichess smoke test:

```bash
docker compose -f deployment/compose/docker-compose.yml --profile lichess-bot up -d lichess-bot
```

The bot uses `http://game-service:8080` inside Compose. Do not configure
`localhost` for `GAME_SERVICE_BASE_URL` in containers.

## Kubernetes Secrets

Create or update the shared secret manually for local or server clusters:

```bash
kubectl create secret generic searchess-secrets \
  -n searchess \
  --from-literal=postgres-password='<postgres password>' \
  --from-literal=grafana-admin-password='<grafana password>' \
  --from-literal=migration-admin-token='' \
  --from-literal=external-game-bot-api-key='<strong shared key>' \
  --from-literal=lichess-bot-token='<lichess bot token>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

In `uni-server-registry`, `searchess-secrets` is managed by Sealed Secrets. Do
not commit plaintext values. Regenerate the sealed secret outside Git with real
values only when explicitly performing a production secret rotation.

The Kubernetes `lichess-bot` Deployment is committed with `replicas: 0` so it
cannot accidentally start. After secrets are present and a smoke test window is
planned:

```bash
kubectl scale deployment/lichess-bot -n searchess --replicas=1
kubectl rollout status deployment/lichess-bot -n searchess
```

Scale it back down after the test if continuous bot operation is not desired:

```bash
kubectl scale deployment/lichess-bot -n searchess --replicas=0
```

## External-Game API Smoke Test

Port-forward Game Service if testing inside Kubernetes:

```bash
kubectl port-forward -n searchess svc/game-service 8080:8080
```

Then verify health and route auth:

```bash
curl -fsS http://localhost:8080/health
curl -i http://localhost:8080/external-games/Lichess/test
```

Expected for the unauthenticated external-game request when the route is mounted:
`401 Unauthorized`.

Create a controlled external binding:

```bash
curl -fsS -X POST http://localhost:8080/external-games \
  -H "Content-Type: application/json" \
  -H "X-Bot-Api-Key: ${EXTERNAL_GAME_BOT_API_KEY}" \
  -d '{"platform":"Lichess","externalGameId":"test","mode":"AiVsExternal","ourColor":"Black","opponentActorId":"smoke-opponent"}'
```

Ingest a cumulative move list:

```bash
curl -fsS -X POST http://localhost:8080/external-games/Lichess/test/moves \
  -H "Content-Type: application/json" \
  -H "X-Bot-Api-Key: ${EXTERNAL_GAME_BOT_API_KEY}" \
  -d '{"uciMoves":"e2e4"}'
```

For `ourColor=Black`, expected `nextAction` is `TriggerAI`.

Request the AI move:

```bash
curl -fsS -X POST http://localhost:8080/external-games/Lichess/test/ai-move \
  -H "Content-Type: application/json" \
  -H "X-Bot-Api-Key: ${EXTERNAL_GAME_BOT_API_KEY}"
```

Expected: a JSON response containing `uciMove`.

## Controlled Lichess Challenge Smoke Test

1. Confirm Game Service has durable persistence and the external-game bot key.
2. Start or scale up `lichess-bot`.
3. Challenge bot account `selinasa` from a test account.
4. Watch logs:

```bash
kubectl logs -n searchess deployment/game-service --tail=100 -f
kubectl logs -n searchess deployment/lichess-bot --tail=100 -f
```

Expected flow:

```text
gameFull -> start external game -> ingest empty moves -> nextAction=TriggerAI
-> ai-move returns uciMove -> lichess-bot submits that move -> echoed gameState is idempotent
```
