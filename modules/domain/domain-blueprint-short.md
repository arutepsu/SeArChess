# domain Blueprint

## Purpose

`domain` contains the core business model and rules of the system.

It should:
- define the core concepts of the game
- enforce chess rules and invariants
- represent the business state and behavior
- stay independent from frameworks and infrastructure

It is the heart of the system.

## It is not

It must not become:
- a transport layer
- a persistence layer
- a UI layer
- a framework-specific module
- a composition root

The domain defines what the system means, not how it is exposed or stored.

## Core rule

All other modules adapt around the domain.

Correct model:

clients / adapters  
-> application  
-> domain

The domain should not depend on transport, database, UI, or runtime modules.

## What it should own

- core game entities and value objects
- chess rules and invariants
- move legality and state transitions
- domain events if part of the model
- pure business behavior

## What it should not own

- HTTP or WebSocket logic
- database access
- DTOs for external APIs
- UI rendering
- runtime wiring

## Main architectural rules

1. `domain` must be independent of frameworks and adapters.
2. Chess rules belong here, not in UI, transport, or persistence modules.
3. Domain models must not leak infrastructure concerns.
4. The domain should remain the single source of business truth.
5. Other modules may depend on domain, but domain must not depend on them.

## Why this matters

This keeps the system clean:

- `domain` = rules and core model
- `application` = use cases and orchestration
- adapters = transport, UI, persistence, AI, events
- `bootstrap-server` = runtime assembly

If this boundary stays clean, the whole architecture stays stable as the system grows.
