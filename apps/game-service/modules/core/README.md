# game-core Blueprint

## Purpose

`game-core` is the Game Service-owned use-case layer.

It should:
- expose the operations the Game Service runtime can perform
- coordinate domain behavior to execute those operations
- depend on ports, not concrete infrastructure
- define stable entry points for Game-owned adapters

Its role is orchestration of active gameplay use cases, not transport or
storage.

The Scala packages intentionally still use names such as
`chess.application.*`. This module rename is an ownership cleanup only; package
cleanup is deferred to a later, lower-risk slice.

## It is not

It must not become:
- the domain model itself
- a transport layer
- a persistence implementation
- a UI layer
- a framework-specific module
- a shared service contract module

`game-core` sits between Game-owned adapters and domain/contract logic.

## Core rule

All Game Service command/query interactions should enter through Game core
boundaries.

Correct model:

Client or adapter  
-> Game core service or command boundary  
-> domain logic  
-> repository ports / event ports  
-> adapters

That makes `game-core` the stable center of the Game Service backend.

## What it should own

- use-case orchestration
- command and query boundaries
- session and game application services
- ports for Game Service persistence and events
- coordination of domain objects to fulfill active gameplay operations

## What it should not own

- HTTP route logic
- WebSocket transport logic
- database access implementations
- framework-specific code
- detailed chess rules that belong in domain
- History-owned archive storage or materialization workflows

## Main architectural rules

1. Game-owned adapters depend on Game core boundaries, not on each other.
2. Game core depends on ports, not concrete infrastructure.
3. State-changing operations should publish events through event ports.
4. Business workflows are coordinated here; transport and persistence stay outside.
5. Game core must stay independent of http4s, WebSocket, databases, and UI frameworks.

## Why this matters

This keeps ownership clear:

- `domain` = business rules and core chess model
- `game-contract` = small shared Game boundary values
- `game-core` = Game Service use cases and orchestration
- adapters = transport, AI, persistence, event delivery
- `game-service` = runtime composition root

If this boundary stays clean, Game Service remains extractable without asking
History or future services to import its internal orchestration module.
