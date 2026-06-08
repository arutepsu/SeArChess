-- Bot turn tasks: durable work queue for the bot-service worker.
--
-- When a HumanVsDeployedBot game reaches a DeployedBot turn, game-service
-- inserts a Pending row here.  bot-service polls GET /internal/bot/turns,
-- which leases the next Pending task (sets status=Leased, lease_until=now+TTL).
-- bot-service submits the chosen move via POST /internal/bot/turns/{id}/move,
-- which validates the lease and applies the move atomically.
--
-- Duplicate-submission safety: once status=Completed the row is never
-- re-transitioned, so a retried move submission is a no-op.
--
-- Status transitions:
--   Pending -> Leased   (on lease grant)
--   Leased  -> Pending  (on lease expiry, re-queued by a background sweeper)
--   Leased  -> Completed (on successful move submission)
--   Leased  -> Failed    (on permanent error, attempt_count >= max)
--   Pending -> Failed    (on game cancellation / session closed)

create table if not exists bot_turn_tasks (
  id              uuid         primary key,
  session_id      uuid         not null,
  game_id         uuid         not null,
  bot_actor_id    text         not null,
  side_to_move    text         not null,
  status          text         not null default 'Pending',
  lease_until     timestamptz,
  attempt_count   integer      not null default 0,
  last_error      text,
  created_at      timestamptz  not null,
  updated_at      timestamptz  not null,
  constraint bot_turn_tasks_status      check (status       in ('Pending', 'Leased', 'Completed', 'Failed')),
  constraint bot_turn_tasks_side        check (side_to_move in ('White',   'Black'))
);

-- Fast index for bot-service polling: only Pending tasks, ordered by age.
create index if not exists bot_turn_tasks_pending_created_idx
  on bot_turn_tasks (created_at)
  where status = 'Pending';

-- Fast lookup of all tasks for a given session (used by cancellation sweep).
create index if not exists bot_turn_tasks_session_idx
  on bot_turn_tasks (session_id);
