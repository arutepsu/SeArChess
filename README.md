
# Chess System — Architecture & Long-Term Plan

[![Coverage Status](https://coveralls.io/repos/github/arutepsu/SeArChess/badge.svg?branch=main)](https://coveralls.io/github/arutepsu/SeArChess?branch=main)

## 1. Project Overview

This project is a modular chess system designed to evolve from a local application into a scalable, extensible architecture.

It aims to support:
- multiple interaction styles (Web UI, TUI, GUI, AI)
- clean separation of concerns
- replaceable infrastructure (persistence, transport, events)
- notation support (FEN, PGN, JSON)
- live updates and event-driven flow
- future extensions such as tournaments and bot APIs

The focus is not only functionality, but **architectural clarity and long-term evolvability**.

---

## 2. Architectural Vision

The system follows a domain-centered, adapter-based architecture:

clients / adapters  
→ application  
→ domain  

Surrounding capabilities:
- notation (independent capability)
- event flow (shared backbone)
- persistence (replaceable infrastructure)
- runtime composition (app-server)

Core principles:
- **domain owns rules**
- **application owns use cases**
- **adapters connect the outside world**
- **runtime assembly is centralized**

---

## 3. Long-Term Module Roles

### domain
The pure chess business core.

Contains:
- board, pieces, moves
- rules (legal moves, turn logic, special moves)
- game state and transitions
- domain errors and invariants

Must remain independent of all frameworks and infrastructure.

---

### application
The use-case and orchestration layer.

Responsible for:
- creating and loading games
- submitting moves
- querying state
- coordinating sessions and lifecycle
- defining ports for persistence and events

Acts as the **stable entry point for all adapters**.

---

### notation
A separable capability for notation handling.

Supports:
- FEN
- PGN
- JSON representations

Provides:
- parsing
- rendering
- multiple parser implementations (regex, fastparse, combinators)

Independent from transport and UI.

---

### adapter-rest-contract
The REST API schema layer.

Defines:
- request/response DTOs
- validation shapes
- serialization contracts

Acts as the **stable interface between backend and clients**.

---

### adapter-rest-http4s
The HTTP transport adapter.

Responsible for:
- route definitions
- request decoding
- response encoding
- HTTP error mapping

Implements the REST contract using http4s.

---

### adapter-websocket
The live update transport adapter.

Responsible for:
- WebSocket connections
- streaming backend events to clients
- subscription handling

Built on top of shared event flow.

---

### adapter-event
The event delivery infrastructure.

Responsible for:
- event publication and subscription
- in-process event distribution
- bridging application events to adapters

Forms the backbone for:
- live updates
- future Kafka integration

---

### adapter-persistence
The storage adapter layer.

Responsible for:
- implementing repository ports
- storing and retrieving state

Long-term:
- split into backend-specific modules (Postgres, Mongo)

---

### adapter-ai
The automated player adapter.

Responsible for:
- AI strategy execution
- decision-making
- invoking application commands

Acts as a **client of the application layer**, not a rule owner.

---

### adapter-tui
Text-based interface.

Used for:
- testing
- debugging
- demonstration
- validating architectural boundaries

---

### adapter-gui
Desktop UI adapter.

Optional long-term role:
- demonstration client
- alternative UI

Maintained only if it continues to validate architecture quality.

---

### app-server (formerly bootstrap-server)
The runtime composition root.

Responsible for:
- configuration
- wiring modules together
- selecting infrastructure
- starting REST and WebSocket endpoints
- managing lifecycle

---

### app-web-ui
The frontend application.

Consumes:
- REST API (via contract)
- WebSocket updates

Responsible for user interaction and presentation.

---

### notation

Independent module for chess notation (FEN, PGN, JSON).

- parsing + rendering
- multiple parser strategies (regex, fastparse, combinators)
- clean split: api + format modules

Independent of UI, transport, and persistence.
Used by the application layer as a reusable capability.
---

## 4. Future Capability Growth

### Game
Core gameplay and rules.

### Live Updates
Event-driven updates via WebSocket and event infrastructure.

### Tournament (future)
- brackets
- standings
- rounds
- player registration

### AI / Bots
- automated players
- external engine integration
- bot APIs

---

## 5. Target Module Structure

### Core
- domain  
- application  
- notation  
- tournament (future)

### Adapters
- adapter-rest-contract  
- adapter-rest-http4s  
- adapter-websocket  
- adapter-event  
- adapter-persistence-postgres  
- adapter-persistence-mongo  
- adapter-ai  
- adapter-bot-api  
- adapter-tui  
- adapter-gui  

### Apps
- app-server  
- app-web-ui  

---

## 6. Architectural Rules

1. Domain is the single source of truth for rules.
2. Application is the only entry point for use cases.
3. Adapters depend on application, not on each other.
4. REST contract defines the public API.
5. Event flow is centralized and shared.
6. Persistence stays behind ports.
7. Runtime assembly happens in app-server only.

---

## 7. Why This Structure Matters

This architecture enables:
- clear separation of concerns
- replaceable infrastructure
- consistent behavior across clients
- scalable evolution (WebSocket, Kafka, microservices)
- easier testing and reasoning

The goal is not premature complexity, but **a structure that supports growth without breaking**.
