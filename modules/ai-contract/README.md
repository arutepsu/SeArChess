# ai-contract

Scope: neutral internal Game <-> AI HTTP wire contract.

This module owns the DTOs, route metadata, and JSON codec for
`inference-api-v1`.

It is intentionally shared by:

- Game Service's outbound `RemoteAiMoveSuggestionClient`
- AI Service's inbound `/v1/move-suggestions` route

It must not contain Game Service orchestration, game rules, persistence,
session lifecycle decisions, or AI engine implementation behavior. Game Service
computes legal moves and validates the returned move. AI Service only selects a
candidate from the supplied contract data.

Local/dev test controls are intentionally kept out of these DTOs. When needed,
adapters may use transport-level hooks such as headers, but those hooks are not
part of the inference task payload.
