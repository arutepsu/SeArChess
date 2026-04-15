# adapter-rest-http4s Blueprint

## 1. Purpose

`adapter-rest-http4s` is the concrete HTTP transport adapter for the backend.

Its job is to expose application use cases over HTTP using http4s.

It is the module where:
- HTTP requests are received
- request DTOs are decoded
- transport validation is applied
- application services are called
- application results are translated into HTTP responses
- route groups are composed into an http4s application

It is the REST engine-specific adapter.

That means this module is responsible for turning the stable REST contract into a running HTTP interface.

## 2. What this module is

`adapter-rest-http4s` is:

- a transport adapter
- framework-specific HTTP wiring
- the place where http4s route logic lives
- the place where REST contract DTOs are connected to application services
- the place where HTTP semantics are decided

It is not:

- a business module
- a persistence module
- a composition root
- a replacement for the application layer
- the owner of the external API contract itself

The API contract should stay centered in `adapter-rest-contract`.
This module is only the http4s realization of that contract.

## 3. Why this module exists separately from adapter-rest-contract

This separation is important and should stay strict.

`adapter-rest-contract` owns:
- request and response DTOs
- transport-facing schema shapes
- shared REST serialization contract
- external API-facing data model

`adapter-rest-http4s` owns:
- route definitions
- request decoding and response encoding with http4s
- status code mapping
- path and endpoint mounting
- error-to-HTTP translation
- middleware integration at the route/app level if needed

This split gives you a durable architecture:

Client
  -> http4s routes
    -> contract DTO decode
      -> application service
        -> application/domain result
      -> contract DTO encode
    -> HTTP response

That lets you preserve the API contract even if the transport engine changes later.

## 4. Long-term role

Long term, `adapter-rest-http4s` should become the authoritative REST transport implementation for the backend.

That means:

- the backend speaks REST through this adapter
- the Web UI talks to this adapter
- bootstrap-server mounts this adapter
- future operational middleware is applied around this adapter
- if another HTTP engine is ever introduced, it should be a separate adapter, not mixed into this one

So the right mental model is:

`adapter-rest-contract` = stable external schema  
`adapter-rest-http4s` = one concrete engine-specific transport implementation of that schema

## 5. Current structure

Current package shape:

- `Http4sApp.scala`
- `route/Http4sGameRoutes.scala`
- `route/Http4sSessionRoutes.scala`
- `route/Http4sRouteSupport.scala`

This is a good start.
It already suggests a useful split between:
- top-level app composition
- feature route groups
- shared route-level support

But the boundaries inside this module must stay sharp, otherwise it will slowly absorb business and mapping logic.

## 6. Responsibilities by file area

### 6.1 `Http4sApp`

Purpose:
- compose the final `HttpRoutes` or `HttpApp`
- mount route groups under stable path prefixes
- combine route modules into one backend-facing HTTP surface
- apply local adapter-level middleware if appropriate

Should own:
- route composition
- prefix mounting
- adapter-level middleware composition
- maybe version prefixing if introduced later

Should not own:
- business orchestration
- request-specific branching logic
- persistence selection
- startup lifecycle
- dependency acquisition

Important:
`Http4sApp` is not the composition root of the whole system.
It is only the composition point of the http4s adapter.

### 6.2 `Http4sSessionRoutes`

Purpose:
- expose session-related HTTP endpoints
- decode session-related requests
- call session-related application services
- return session-related responses

Should own:
- session endpoint paths
- HTTP method selection
- request decoding
- contract DTO usage
- status code selection for session scenarios

Should not own:
- session creation business logic
- repository access
- game lifecycle rules
- cross-route shared infrastructure setup

### 6.3 `Http4sGameRoutes`

Purpose:
- expose game-related HTTP endpoints
- decode game requests
- call game-oriented application services
- translate application results into HTTP responses

Should own:
- game endpoint definitions
- move submission endpoint behavior at HTTP level
- read/query endpoint response generation
- endpoint-local validation of transport input shape

Should not own:
- chess rules
- move legality implementation
- persistence access
- event distribution logic

### 6.4 `Http4sRouteSupport`

Purpose:
- centralize reusable HTTP-level support used by multiple route groups

Good candidates:
- common response helpers
- shared error rendering helpers
- common decoding/validation utilities
- reusable route directives/patterns
- shared status mapping conventions

Risk:
This file can become a hidden junk drawer very fast.

So it should not become:
- a service locator
- a mapper replacement
- a business helper bag
- a place to hide route logic that should live in route modules

The standard should be:
if a concern is reusable and purely HTTP/route-facing, it may live here.
If it is business-facing or contract-shaping, it belongs elsewhere.

## 7. Inputs to adapter-rest-http4s

This module should consume three main categories of inputs.

### A. Application services

These are the use-case entry points the routes call.

Examples:
- session command service
- session query service
- game command service
- game query service

The routes should depend on these abstractions or stable service boundaries, not on repositories or domain internals.

### B. REST contract types

These come from `adapter-rest-contract`.

Examples:
- request DTOs
- response DTOs
- standardized error response shapes
- mapping helpers if those remain in the contract layer

This module should use the contract, not redefine it.

### C. HTTP-level support and middleware

Examples:
- entity decoders/encoders
- route support utilities
- auth middleware later if added
- CORS/logging/metrics middleware around the app, depending on where you place them

## 8. Outputs of adapter-rest-http4s

Conceptually, this module should produce one thing:

A framework-specific HTTP application surface.

In practice, that means:
- composed `HttpRoutes`
- or a final `HttpApp`
- representing the REST API of the backend

This output is consumed by `bootstrap-server`, which then mounts and runs it inside the backend process.

So this module should stop at:
“Here is the HTTP adapter”
not:
“Here is how the whole backend starts”

## 9. Correct dependency direction

The dependency direction should be:

`adapter-rest-http4s`
  depends on `adapter-rest-contract`
  depends on application ports/services
  depends on http4s libraries

It must not pull the architecture backwards.

Bad direction:
- application depends on http4s adapter
- contract depends on http4s-specific types
- domain depends on contract DTOs

Good direction:
- domain is independent
- application is independent of transport
- contract defines transport shapes
- http4s adapter realizes transport behavior using contract and application services

## 10. Mapping responsibility

This is one of the most important boundaries.

The http4s adapter should not invent its own external shapes.
It should rely on the contract layer for the external schema.

That means:
- request DTOs come from `adapter-rest-contract`
- response DTOs come from `adapter-rest-contract`
- domain/application objects should not be serialized directly from routes
- route code should stay thin and translation-focused

A healthy route flow looks like this:

HTTP request
  -> decode request DTO
  -> perform transport validation
  -> call application service
  -> map application result to contract response DTO
  -> encode HTTP response

If mappers exist in `adapter-rest-contract`, they should stay schema-oriented and transport-contract-oriented.
If a mapping becomes business orchestration, it has crossed the line.

## 11. Error handling responsibility

`adapter-rest-http4s` should own HTTP error translation.

That includes:
- invalid request body -> 400
- malformed input -> 400
- not found use-case result -> 404
- conflict/invalid state -> 409 or appropriate status
- unexpected failure -> 500

This module should decide:
- which HTTP status code represents which application outcome
- which error DTO is returned
- whether errors are consistent across route groups

It should not decide:
- what the business rules are
- what counts as a domain invariant
- how domain logic resolves ambiguous state

The application/domain decides meaning.
The transport adapter decides HTTP expression.

## 12. Validation boundary

There are two different validation layers, and they must not be mixed.

### Transport validation
Owned here or in the contract boundary.

Examples:
- required JSON field missing
- field has wrong type
- unsupported enum shape at transport level
- malformed move notation format if considered request-shape validation

### Business validation
Owned in application/domain.

Examples:
- move is illegal in current game state
- session cannot transition in this way
- command violates rules of the chess workflow

Routes may reject malformed input.
They must not implement chess logic.

## 13. Event flow rule

Routes should not become the event backbone.

Correct model:

Client
  -> REST route
    -> application service
      -> state change
      -> event publication
        -> event adapter
          -> websocket consumers / later Kafka bridge

That means `adapter-rest-http4s`:
- triggers commands through application services
- does not manually fan out live updates itself
- does not bypass the shared event flow

This rule matters because otherwise the REST adapter becomes the secret integration hub.

## 14. What this module must not contain

`adapter-rest-http4s` must not become a place for:

- repository construction
- database access
- persistence selection
- app startup logic
- environment/profile selection
- business use-case implementation
- shared API contract ownership
- websocket event distribution logic
- hidden service assembly

If you ever see route code doing direct repository calls, the module is drifting.

If you ever see route code constructing services internally, the module is drifting.

If you ever see http4s types appear in contract or application, the boundary is leaking.

## 15. Mounting blueprint

A good long-term mount structure for this adapter is:

- `/api/sessions`
- `/api/games`
- later `/api/notation`

And then `bootstrap-server` mounts:
- REST app from this module
- WebSocket endpoint from websocket adapter
- health/metrics/admin endpoints from bootstrap or dedicated adapters

Important:
the mount policy of the REST API should be centralized in this adapter or clearly coordinated with bootstrap.
Do not scatter path ownership.

## 16. Recommended internal conceptual substructure

Even if you do not physically split it immediately, the conceptual structure should be:

- `route`
  - feature route groups by use-case area
- `support`
  - shared HTTP support helpers
- `app`
  - final route/app composition
- maybe later `error`
  - error encoders/status mappings if they grow
- maybe later `codec`
  - explicit codecs if serialization complexity increases

With the current files, the likely mapping is:

- `Http4sApp` -> app composition
- `Http4sGameRoutes` -> feature routes
- `Http4sSessionRoutes` -> feature routes
- `Http4sRouteSupport` -> shared support

That is fine for now.
Just do not let support become a generic bucket.

## 17. Recommended growth path

As the project evolves, this module will likely need to support:

- richer error mapping
- versioned API paths
- auth/authz middleware
- request tracing or correlation IDs
- metrics instrumentation
- pagination/filtering conventions
- more route groups
- more explicit codec organization

When that happens, the response should be:
split HTTP concerns more clearly inside the adapter

not:
push more logic into route files until they become miniature application services

## 18. Responsibilities matrix

### `adapter-rest-http4s` owns
- http4s route definitions
- HTTP method and path exposure
- request decode / response encode
- status code mapping
- transport-level error rendering
- composition of REST route groups
- framework-specific middleware at the adapter level

### `adapter-rest-http4s` does not own
- external contract definitions
- chess business rules
- application orchestration logic
- repository implementations
- persistence runtime selection
- process lifecycle
- websocket live-update backbone
- environment/config composition

## 19. Architectural rules

Rule 1  
This module depends on the contract layer; the contract layer must not depend on http4s.

Rule 2  
All route handlers call application services, never repositories directly.

Rule 3  
All external request and response shapes come from the contract layer, not ad hoc route-local case classes.

Rule 4  
HTTP concerns are handled here; business concerns are handled in application/domain.

Rule 5  
This module produces an HTTP adapter surface; `bootstrap-server` owns runtime startup and mounting into the overall backend process.

Rule 6  
State-changing requests must enter the system through application service boundaries.

Rule 7  
Live update propagation must come from shared event flow, not route-local publishing logic.

## 20. Most important design choice

The most important design choice here is to keep `adapter-rest-http4s` thin but authoritative.

Thin means:
- no business logic
- no persistence logic
- no hidden assembly

Authoritative means:
- it is the real HTTP transport adapter
- it owns HTTP semantics clearly
- it consistently applies the contract layer
- it becomes the single place where the backend's REST behavior is expressed in http4s

If you get this balance right, the backend stays testable, replaceable, and evolvable.

## 21. Final compact version

`adapter-rest-http4s` is the concrete http4s-based REST transport adapter.

It should:
- expose REST endpoints
- decode contract requests
- call application services
- map application results to HTTP responses
- compose route groups into a single REST app

It must not:
- own business logic
- own persistence
- own startup/runtime composition
- redefine the REST contract
- bypass application boundaries

So the intended split is:

- `adapter-rest-contract` = stable REST-facing schema
- `adapter-rest-http4s` = http4s implementation of that schema
- `bootstrap-server` = runtime composition root that mounts and runs it
