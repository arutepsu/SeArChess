# adapter-rest-http4s Blueprint

## Purpose

`adapter-rest-http4s` is the concrete HTTP transport adapter.

It should:
- expose REST endpoints with http4s
- decode request DTOs from `adapter-rest-contract`
- call application services
- translate application results into HTTP responses
- compose the REST routes into one http4s app

It is the http4s implementation of the REST contract.

## It is not

It must not become:
- business logic
- persistence logic
- startup/runtime composition
- a second application layer
- the owner of the REST contract itself

`adapter-rest-contract` owns the external schema.  
`game-service` owns runtime assembly and startup.

## Current structure

- `Http4sApp.scala`  
  Composes the final REST app and mounts route groups.

- `route/Http4sSessionRoutes.scala`  
  Session-related HTTP endpoints.

- `route/Http4sGameRoutes.scala`  
  Game-related HTTP endpoints.

- `route/Http4sRouteSupport.scala`  
  Shared HTTP helpers only.

## Correct dependency flow

Client  
-> http4s route  
-> contract DTO decode  
-> application service  
-> application/domain result  
-> contract DTO encode  
-> HTTP response

This is the key rule:
routes talk to application services, never directly to repositories.

## What each part should own

### `Http4sApp`
Owns:
- route composition
- path mounting
- adapter-level middleware if needed

Should not own:
- business orchestration
- persistence selection
- process startup

### `Http4sSessionRoutes` and `Http4sGameRoutes`
Own:
- endpoint paths and methods
- request decoding
- calling application services
- status code mapping
- response rendering

Should not own:
- chess rules
- repository access
- hidden service construction

### `Http4sRouteSupport`
Owns:
- shared HTTP helpers
- common response/error helpers
- reusable route-level utilities

Should not become:
- a junk drawer
- a business helper bag
- a hidden assembly layer

## Main architectural rules

1. `adapter-rest-http4s` depends on `adapter-rest-contract`, not the other way around.
2. Routes use contract DTOs, not domain objects as public API shapes.
3. Routes call application services, never repositories directly.
4. Transport validation belongs here; business validation belongs in application/domain.
5. REST-triggered state changes must publish events through the shared event flow, not through route-local live update logic.

## Why this matters

This keeps the backend clean:

- `adapter-rest-contract` = stable external REST schema
- `adapter-rest-http4s` = concrete HTTP transport adapter
- `game-service` = runtime composition root

That separation is what will keep Web UI integration, testing, and later microservice evolution manageable.
