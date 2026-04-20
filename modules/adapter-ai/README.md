# adapter-ai Blueprint

## Purpose

`adapter-ai` is the automated player adapter.

It should:
- observe game state through approved boundaries
- choose actions for an AI-controlled player
- call application services or command interfaces to perform those actions

Its role is to act as a machine player, not to own game rules.

## It is not

It must not become:
- a business module
- a persistence module
- a hidden controller
- direct state mutation logic
- a second application layer

The AI should use the same application boundaries as any other client.

## Core rule

AI is just another client of the application layer.

Correct model:

AI adapter  
-> application command boundary  
-> domain/application logic  
-> state change  
-> event publication

Not:

AI adapter  
-> repository or game internals  
-> mutate state directly

## What it should own

- AI strategy integration
- move or action selection
- translation from strategy decisions into application commands
- optional triggering when it is the AI player's turn

## What it should not own

- chess legality rules
- turn rules
- repository access
- transport contracts
- event distribution
- runtime composition

## Main architectural rules

1. AI actions must go through application services or command boundaries.
2. AI must not bypass the same rules enforced for human players.
3. AI strategy should be replaceable without changing application logic.
4. `adapter-ai` may choose actions, but application/domain decides whether they are valid.
5. `game-service` wires this adapter into the runtime; `adapter-ai` does not start or assemble the system.

## Why this matters

This keeps the backend clean:

- application/domain = rules and use cases
- `adapter-ai` = automated decision-making client
- `adapter-event` = shared event backbone
- `game-service` = runtime composition root

That separation keeps AI testable, replaceable, and aligned with the same source of truth as every other client.
