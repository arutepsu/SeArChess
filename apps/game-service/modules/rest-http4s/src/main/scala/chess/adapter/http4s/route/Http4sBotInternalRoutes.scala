package chess.adapter.http4s.route

/** Skeleton for the game-service internal bot polling API.
  *
  * NOT yet implemented.  Routes will be added in Step 3 after explicit approval.
  * This file documents the design so that bot-service and game-service can be
  * developed in parallel.
  *
  * =Polling design (Step 3)=
  *
  * ==Overview==
  * game-service owns the durable work queue (`bot_turn_tasks`).
  * bot-service is a stateless consumer: it polls, leases, computes, and submits.
  * No Lichess API is involved.
  *
  * ==Endpoint 1: Lease a pending bot turn==
  *
  * {{{
  *   GET /internal/bot/turns?actorId=searchess-bot&limit=1
  *   X-Bot-Api-Key: <BOT_WORKER_API_KEY>
  *
  *   200 OK
  *   {
  *     "turnTaskId": "<uuid>",
  *     "sessionId":  "<uuid>",
  *     "gameId":     "<uuid>",
  *     "sideToMove": "Black",
  *     "legalMoves": [ { "from": "e7", "to": "e5" }, ... ],
  *     "leaseUntil": "2026-06-08T12:00:30Z"
  *   }
  *
  *   204 No Content  — no pending tasks for this actor
  *   401 Unauthorized — bad or missing X-Bot-Api-Key
  * }}}
  *
  * ==Server-side behaviour for GET /internal/bot/turns==
  * 1. Authenticate via `X-Bot-Api-Key` → resolve `botActorId`.
  * 2. `SELECT ... FOR UPDATE SKIP LOCKED WHERE status='Pending' AND bot_actor_id=? LIMIT ?`
  * 3. Transition matched rows: `status='Leased'`, `lease_until=now+60s`,
  *    `attempt_count=attempt_count+1`.
  * 4. Return task details including current legal moves from game state.
  * 5. If no row found, return 204.
  *
  * ==Endpoint 2: Submit move for a leased turn==
  *
  * {{{
  *   POST /internal/bot/turns/{turnTaskId}/move
  *   X-Bot-Api-Key: <BOT_WORKER_API_KEY>
  *   Content-Type: application/json
  *
  *   { "from": "e7", "to": "e5" }
  *
  *   200 OK  — move applied; game state returned
  *   409 Conflict — lease expired or task already completed
  *   422 Unprocessable — illegal move
  *   401 Unauthorized
  * }}}
  *
  * ==Server-side behaviour for POST /internal/bot/turns/{id}/move==
  * Atomically (inside a single DB transaction):
  * 1. Authenticate `X-Bot-Api-Key` → resolve `botActorId`.
  * 2. Load task; verify `status='Leased'` and `lease_until > now` and
  *    `bot_actor_id = requestingActorId`.
  * 3. Load session; verify `lifecycle != Finished/Cancelled`.
  * 4. Load game; verify it is still the bot's turn (`currentPlayer == sideToMove`).
  * 5. Apply move via game-service command (validates legality).
  * 6. Transition task: `status='Completed'`, `updated_at=now`.
  * 7. Return updated game snapshot.
  *
  * ==Duplicate-submission safety==
  * If game-service receives a second submission for a task already in
  * `status='Completed'`, it returns `200 OK` with the current game state
  * without re-applying the move.
  *
  * ==Lease expiry / retry==
  * A background sweeper (or triggered on polling) re-queues expired leases:
  * `UPDATE bot_turn_tasks SET status='Pending' WHERE status='Leased' AND lease_until < now()`
  * `AND attempt_count < max_attempts`
  *
  * Tasks that exceed `max_attempts` transition to `status='Failed'`.
  *
  * ==Authentication==
  * `X-Bot-Api-Key` maps to a [[chess.adapter.http4s.route.BotCredentials]]-style
  * secret known only to bot-service and game-service.  This is separate from the
  * Keycloak JWT used by the public API.  The existing
  * [[chess.adapter.http4s.route.ExternalGameRouteAuth]] mechanism can be reused
  * with a dedicated bot-service credential entry.
  *
  * ==Step 3 implementation checklist==
  *   - [ ] `Http4sBotInternalRoutes` with `GET /internal/bot/turns` and
  *         `POST /internal/bot/turns/{id}/move`
  *   - [ ] `BotTurnTaskRepository` (Postgres) + in-memory stub
  *   - [ ] `BotTurnService` — lease/submit/expire logic
  *   - [ ] bot-service: polling loop calling these endpoints
  *   - [ ] bot-service: ai-service call for move selection
  *   - [ ] k8s: bump bot-service `replicas` from 0 to 1
  *   - [ ] k8s: set `BOT_WORKER_API_KEY` secret in overlays
  */
object Http4sBotInternalRoutes:
  // Placeholder — no routes yet.
  // Implementation begins in Step 3.
  val notYetImplemented: Unit = ()
