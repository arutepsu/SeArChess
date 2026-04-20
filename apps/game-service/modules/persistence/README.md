# adapter-persistence Blueprint

## Purpose

`adapter-persistence` is the infrastructure adapter for state storage.

It should:
- implement repository ports required by the application layer
- persist and load backend state
- hide storage details behind stable interfaces
- remain replaceable as storage technology changes

Its role is data access, not business logic.

## It is not

It must not become:
- a business module
- an application service layer
- a transport adapter
- a place for workflow orchestration
- a source of domain rules

The application defines what must be stored.  
`adapter-persistence` defines how it is stored.

## Core rule

Persistence sits behind application repository ports.

Correct model:

Client  
-> REST adapter  
-> application service  
-> repository port  
-> `adapter-persistence`

Not:

Client  
-> REST adapter  
-> database directly

And not:

`adapter-persistence`  
-> business decisions  
-> application orchestration

## What it should own

- concrete repository implementations
- database or storage access
- serialization or mapping to persistence models
- resource usage needed for storage access
- loading and saving state through repository boundaries

## What it should not own

- chess rules
- use-case orchestration
- HTTP or WebSocket behavior
- event distribution
- runtime profile selection
- direct client-facing API shapes

## Main architectural rules

1. Application depends on repository ports; persistence implements them.
2. Transport adapters must not access persistence directly.
3. Persistence models must not become the public API or domain model by accident.
4. Storage technology choice belongs to `game-service`, not to routes or services.
5. `adapter-persistence` must stay replaceable for in-memory, Postgres, Mongo, or other backends later.

## Why this matters

This keeps the architecture clean:

- application = use cases and repository ports
- `adapter-persistence` = concrete storage implementation
- transport adapters = client access only
- `game-service` = selects which persistence adapter is active

That separation is what makes testing, swapping storage backends, and later microservice evolution manageable.
