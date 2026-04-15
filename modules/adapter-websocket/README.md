# adapter-websocket Blueprint

## Purpose

`adapter-websocket` is the live update transport adapter.

It should:
- expose a WebSocket endpoint
- subscribe clients to backend events
- stream application-emitted events to connected clients
- translate internal event flow into transport-facing live messages

Its role is live delivery, not business execution.

## It is not

It must not become:
- a second backend
- a place for business logic
- a place for state mutation rules
- a persistence access layer
- a replacement for application services

If clients ever send commands through WebSocket, those commands must still go through application services.

## Core rule

WebSocket must sit on top of the shared event flow.

Correct model:

Client  
-> REST adapter  
-> application service  
-> state change  
-> event publication  
-> event adapter  
-> WebSocket subscribers

And separately:

Client  
-> WebSocket adapter  
-> receives shared backend events

That means WebSocket does not invent its own state model.

## What it should own

- WebSocket endpoint definition
- connection/session handling for subscribers
- subscription to shared event distribution
- transport encoding of live messages
- disconnect/cleanup behavior

## What it should not own

- chess rules
- repository calls
- session/game orchestration
- route-independent business decisions
- event production logic for state changes

## Main architectural rules

1. WebSocket consumes shared events; it does not bypass the application layer.
2. REST and WebSocket must observe the same backend truth.
3. Live updates come from application-emitted events, not ad hoc route-local pushes.
4. If WebSocket supports inbound commands later, they must be forwarded into application services.
5. `bootstrap-server` mounts this adapter; it does not start the system by itself.

## Why this matters

This keeps the architecture clean:

- `adapter-rest-http4s` = command/query HTTP transport
- `adapter-websocket` = live event transport
- shared event flow = single update backbone
- `bootstrap-server` = runtime composition root

That separation is what makes live UI updates, reactive streams, and later Kafka/microservice evolution much easier.
