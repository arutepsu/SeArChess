# game-service Blueprint

## Purpose

`game-service` is the runtime composition root.

It should:
- assemble the system from modules
- select concrete infrastructure (persistence, events, etc.)
- wire application, adapters, and resources together
- start and stop the backend process

It turns the architecture into a running system.

## It is not

It must not become:
- a business module
- a transport adapter
- a persistence module
- a place for domain logic

## Core rule

All wiring happens here.

Correct model:

game-service  
-> creates infrastructure  
-> wires application services  
-> connects adapters (REST, WebSocket, AI, etc.)  
-> starts server

## What it should own

- runtime configuration (ports, profiles, flags)
- selection of persistence and event implementations
- creation of resources (DB, event system, server)
- wiring of application services to adapters
- mounting REST and WebSocket endpoints
- lifecycle (startup/shutdown)

## What it should not own

- chess rules
- use-case logic
- route logic
- repository internals

## Main architectural rules

1. All adapters are wired here, not inside each other.
2. Application services receive concrete implementations here.
3. Transport adapters must not construct dependencies themselves.
4. Persistence choice is made here, not in application or routes.
5. Startup should fail fast if dependencies are not ready.

## Why this matters

This keeps the system clean:

- `application` = use cases  
- `adapters` = transport/infrastructure  
- `game-service` = assembly + runtime  

If this stays clean, the backend remains modular, testable, and ready for scaling (microservices, Kafka, etc.).
