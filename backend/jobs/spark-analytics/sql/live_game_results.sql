-- Table created at job startup by LiveGameResultsSink.ensureTableExists().
-- This file is a reference for manual inspection or ad-hoc creation.
-- Run against the searchess Postgres instance (public schema).

CREATE TABLE IF NOT EXISTS public.live_game_results (
    event_id       TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    session_id     TEXT,
    occurred_at    TIMESTAMPTZ NOT NULL,
    result         TEXT        NOT NULL,
    winner         TEXT,
    draw_reason    TEXT,
    correlation_id TEXT,
    ingested_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT live_game_results_pk PRIMARY KEY (event_id)
);
