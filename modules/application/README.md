# application Blueprint

## Purpose

`application` is the use-case layer of the system.

It should:
- expose the operations the outside world can perform
- coordinate domain behavior to execute those operations
- depend on ports, not concrete infrastructure
- define stable entry points for adapters

Its role is orchestration of use cases, not transport or storage.

## It is not

It must not become:
- the domain model itself
- a transport layer
- a persistence implementation
- a UI layer
- a framework-specific module

The application layer sits between adapters and domain logic.

## Core rule

All external interactions should enter through application boundaries.

Correct model:

Client or adapter  
-> application service or command boundary  
-> domain logic  
-> repository ports / event ports  
-> adapters

That makes the application layer the stable center of the backend.

## What it should own

- use-case orchestration
- command and query boundaries
- session and game application services
- ports for persistence and events
- coordination of domain objects to fulfill operations

## What it should not own

- HTTP route logic
- WebSocket transport logic
- database access implementations
- framework-specific code
- detailed chess rules that belong in domain

## Main architectural rules

1. All adapters depend on application boundaries, not on each other.
2. Application depends on ports, not concrete infrastructure.
3. State-changing operations should publish events through event ports.
4. Business workflows are coordinated here; transport and persistence stay outside.
5. The application layer must stay independent of http4s, WebSocket, databases, and UI frameworks.

## Why this matters

This keeps the architecture clean:

- domain = business rules and core model
- application = use cases and orchestration
- adapters = transport, AI, persistence, event delivery
- `bootstrap-server` = runtime composition root

If this boundary stays clean, the system remains testable, extensible, and ready for later growth.
