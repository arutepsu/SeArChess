# adapter-event Blueprint

## Purpose

`adapter-event` is the event distribution adapter.

It should:
- receive application-emitted events
- distribute them to interested consumers
- provide the shared event backbone for live updates
- stay replaceable as event delivery evolves

Its job is event transport and distribution, not business logic.

## It is not

It must not become:
- a business module
- a second application layer
- a place that invents domain events on its own
- a persistence module
- a WebSocket module
- a Kafka-specific architecture today

The application decides which events are emitted.  
`adapter-event` only carries them.

## Core role

This module is the shared backbone between state changes and live consumers.

Correct model:

Client  
-> REST adapter  
-> application service  
-> state change  
-> event publication port  
-> `adapter-event`  
-> WebSocket subscribers  
-> later Kafka bridge / other consumers

That is the key shape to protect.

## What it should own

- concrete event publication/subscription mechanism
- in-process event distribution
- subscriber registration and cleanup
- event fan-out to multiple consumers
- a stable adapter boundary for future external bridges

## What it should not own

- chess rules
- application orchestration
- repository access
- HTTP route behavior
- WebSocket connection handling
- transport-specific DTO ownership

## Main architectural rules

1. Application emits events; `adapter-event` distributes them.
2. REST routes must not publish live updates directly to clients.
3. WebSocket should consume the shared event flow from here.
4. This module should support evolution from in-process events today to external bridges later.
5. Event distribution must stay independent from transport and persistence details.

## Why this matters

This module is the architectural hinge for:
- live Web UI updates
- reactive/event-driven flow
- future Kafka integration
- later microservice evolution

Clean split:

- application = emits meaningful events
- `adapter-event` = distributes them
- `adapter-websocket` = streams them to clients
- `game-service` = wires everything together

If this boundary stays clean, the system can evolve without turning REST or WebSocket into the integration hub.
