# Service Contract Map

This directory documents service boundaries by audience, not by implementation
convenience.

| Contract | Audience | Owner | Interaction | Edge exposure |
|---|---|---|---|---|
| `game-service-http-v1.md` | public edge | Game Service | client/UI -> Game HTTP and WebSocket | exposed through Envoy as `/health`, `/api/*`, and `/ws/*` |
| `inference-api-v1.md` | internal sync | neutral `ai-contract` DTOs; AI Service implements; Game Service consumes | Game -> AI synchronous HTTP | not exposed through Envoy |
| `history-service-http-v1.md` | internal downstream | History Service | Game -> History at-least-once terminal-event delivery | not exposed through Envoy |
| `game-service-http-v1.md` `GET /archive/games/{gameId}` | internal read use of Game HTTP v1 | Game Service | History -> Game archive snapshot fetch | currently available behind Game's Envoy `/api/*` route for clients too |
| `game-events-v1.md` | internal async payload | Game Service | versioned terminal event JSON used by History ingestion | not exposed directly |

History-owned archive reads at `GET /archives/{gameId}` are internal for now.
If History archive reads become edge-facing later, introduce a separate public
History archive contract instead of silently expanding the existing internal
downstream contract.

The History -> Game archive snapshot fetch currently reuses the Game Service
HTTP v1 archive-read shape. That is acceptable for this local/dev topology, but
any future divergence between public client archive reads and History ingestion
needs an explicit contract split.

Compatibility aliases must be isolated, documented, and explicitly configured.
The legacy History ingestion alias `POST /events/game` is temporary and disabled
by default; the canonical downstream path is `POST /internal/events/game`.
