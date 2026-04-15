# bootstrap-server Blueprint

## Status and intent

`bootstrap-server` is the **runtime composition root** of the backend.

Its job is to take the project’s module graph and turn it into **one coherent running backend process**. This means it is responsible for selecting runtime infrastructure, wiring module boundaries together, composing external interfaces, and managing process lifecycle.

It is the place where the abstract architecture becomes a runnable deployment.

That role aligns with the course trajectory as well: HTTP, Web UI, persistence, eventing, and later microservices all need a stable composition root. The current direction should therefore optimize not only for “start a server now”, but for a backend shape that can evolve cleanly later.

## What bootstrap-server is

`bootstrap-server` **is**:

- the runtime composition root
- the place where concrete adapters are selected
- the place where infrastructure resources are acquired and released
- the place where REST, WebSocket, and operational endpoints are composed
- the entry point that starts and stops the backend safely

## What bootstrap-server is not

`bootstrap-server` is **not**:

- a business module
- a transport adapter
- a persistence module
- a second application layer
- a place for chess rules or route-specific business logic

That boundary matters. Once startup modules start owning business behavior, they become impossible to reason about and painful to evolve.

---

## Inputs to bootstrap-server

Conceptually, `bootstrap-server` consumes four categories of inputs.

### 1. Configuration inputs

These define runtime behavior.

Examples:

- HTTP host and port
- environment profile: `dev`, `test`, `prod`
- active persistence backend: `in-memory`, `Postgres`, `Mongo`
- WebSocket enabled or disabled
- CORS policy
- logging and metrics flags
- event mode: in-process now, bridgeable later
- feature toggles for optional capabilities

### 2. Module contracts

These are the ports and public construction boundaries exposed by other modules.

Examples:

- application service interfaces
- repository ports
- event publication/subscription ports
- REST contract models
- notation service boundary

### 3. Infrastructure resources

These are runtime resources that require managed acquisition and release.

Examples:

- database connection pools
- HTTP server resource
- event bus or streaming infrastructure
- background stream resources
- scheduler / clock resources later if needed

### 4. Deployment profile decisions

These are runtime choices based on environment.

Examples:

- use in-memory persistence in tests
- use Postgres in staging or production
- disable WebSocket in minimal profile
- enable metrics only in selected environments

---

## Output of bootstrap-server

Conceptually, `bootstrap-server` should produce **one thing**:

> a coherent running backend process

That process exposes:

- REST API
- WebSocket endpoint
- later health / metrics / admin endpoints

And it is backed by:

- assembled application services
- selected persistence adapter
- selected event distribution mechanism
- chosen runtime profile

So the output is not just “an Ember server”.
It is a fully assembled backend runtime.

---

## Internal assembly areas

To keep the module durable, `bootstrap-server` should be understood as five assembly areas.

### 1. Configuration assembly

**Purpose**

- collect runtime settings
- validate required parameters
- normalize runtime config

**Owns decisions like**

- which host and port to bind
- which persistence adapter is selected
- whether WebSocket is active
- whether CORS, metrics, and dev options are enabled

**Produces**

- a normalized runtime configuration model consumed by the rest of bootstrap

### 2. Infrastructure assembly

**Purpose**

- create concrete infrastructure implementations and managed resources

**Owns**

- persistence adapter creation
- event adapter creation
- future Kafka bridge creation
- connection pools
- metrics/tracing resources later

**Produces**

- concrete repository implementations
- concrete event distribution implementation
- acquired runtime resources

Important: this area creates infrastructure, but must not embed business rules.

### 3. Application assembly

**Purpose**

- wire application services from ports and implementations

**Owns**

- command service construction
- query service construction
- session service construction
- notation orchestration if exposed through application
- event publication port binding

**Produces**

- stable application-facing services that transports depend on

Important: this area wires use cases; it does not implement them.

### 4. Interface composition

**Purpose**

- assemble external protocols into one coherent backend boundary

**Owns**

- REST route construction through `adapter-rest-http4s`
- WebSocket endpoint construction through `adapter-websocket`
- path mounting and top-level prefixes
- endpoint combination into one public surface

**Produces**

- the final external protocol surface such as:
  - `/api/...`
  - `/ws`
  - later `/health`, `/metrics`

### 5. Runtime lifecycle

**Purpose**

- start and stop the process safely

**Owns**

- acquisition order of resources
- final server startup
- host / port binding
- coordinated shutdown and cleanup

**Produces**

- a live backend instance with predictable lifecycle behavior across profiles

---

## What bootstrap-server wires

### A. Core application dependencies

`bootstrap-server` wires:

- application services to repository implementations
- application services to event publication implementation
- application services to notation boundary if needed
- application services to AI integration if needed later

This is where abstract application ports receive concrete implementations.

### B. Transport adapters to application services

`bootstrap-server` wires:

- REST routes to command/query/session services
- WebSocket endpoint to event/subscription flow
- later admin or bot endpoints to application services

Transport adapters must not resolve their own dependencies through hidden construction logic.

### C. Event flow across the backend

This is a critical responsibility.

`bootstrap-server` should wire:

- application event publication port
- in-process event bus or event distribution mechanism
- WebSocket subscription endpoint onto that stream
- later Kafka bridge onto the same event flow

This is the key move that makes later evolution clean.

### D. Selected persistence profile

`bootstrap-server` should decide:

- which repository implementation is active
- which storage backend is used in this run
- how repositories are shared across services

That allows persistence to vary without changing application logic.

---

## What bootstrap-server must not wire directly

### A. It must not wire routes directly to databases

**Bad**

- REST route depends directly on Postgres repository
- WebSocket endpoint queries Mongo directly

**Good**

- both depend on application services
- application services depend on ports
- bootstrap selects the concrete implementation

### B. It must not let WebSocket become a second business path

**Bad**

- WebSocket endpoint owns state mutation logic
- live transport bypasses application services

**Good**

- WebSocket consumes shared event flow
- if it later accepts commands, they still go through application services

### C. It must not absorb business rules into startup code

**Bad**

- chess move handling inside bootstrap
- route-specific game logic in startup code

**Good**

- bootstrap assembles collaborators only

---

## Correct composition model

The intended architectural composition is:

```text
Client
  -> REST adapter
    -> application service
      -> domain logic
      -> repository port -> persistence adapter
      -> event publication port -> event adapter
                                   -> websocket subscribers
                                   -> later kafka bridge
```

And separately:

```text
Client
  -> WebSocket adapter
    -> subscription / event stream
      <- event adapter
         <- application-emitted events
```

This gives the backend the right center of gravity:

- one source of truth for mutations
- one shared event backbone
- persistence behind application ports
- transport independence

This is the most important architectural shape to preserve.

---

## Top-level mount blueprint

`bootstrap-server` should own the top-level external surface.

Suggested structure:

```text
/api/sessions
/api/games
/api/notation      later if exposed
/ws
/health            later
/metrics           later
```

This gives:

- stable public structure
- room for growth
- centralized mounting decisions
- clean operational extension points

---

## Startup order

Recommended order:

### Step 1
Load and validate runtime configuration.

### Step 2
Create infrastructure resources.
Examples:

- DB connections
- event infrastructure
- streaming resources

### Step 3
Construct concrete repositories and infrastructure adapters.

### Step 4
Construct application services.

### Step 5
Construct transport adapters.
Examples:

- REST routes
- WebSocket endpoint

### Step 6
Compose the final backend surface.

### Step 7
Start the HTTP server.

This order reduces ambiguity and makes startup failures easier to reason about.

---

## Startup failure model

A good `bootstrap-server` should treat startup as all-or-nothing.

Principles:

- fail fast on invalid config
- fail before binding network ports if dependencies are not ready
- avoid partial startup where REST is live but eventing is broken
- prefer one coherent “backend up” state over fragmented availability

That means:

> either the required runtime is assembled correctly, or the process does not start

This becomes even more important once persistence and streaming become real runtime dependencies.

---

## Responsibility matrix

### bootstrap-server owns

- runtime configuration
- concrete adapter selection
- resource lifecycle
- application assembly
- protocol composition
- final server startup

### bootstrap-server does not own

- chess rules
- use-case logic
- DTO definitions
- route business behavior
- repository internals
- event semantics themselves

---

## Architectural rules for bootstrap-server

These rules should be explicit and stable.

### Rule 1
`bootstrap-server` may depend on many modules; other modules must not depend on it.

### Rule 2
`bootstrap-server` assembles collaborators but does not implement business flows.

### Rule 3
All transport adapters are mounted from `bootstrap-server`, not from each other.

### Rule 4
All state-changing client interactions go through application services.

### Rule 5
WebSocket live updates are fed from shared event flow, not from a separate state model.

### Rule 6
Persistence implementations are selected in `bootstrap-server`, not in transport adapters.

### Rule 7
Operational concerns like health, metrics, profiles, and later tracing live at the bootstrap level.

---

## Recommended conceptual substructure

Even if you do not create all packages immediately, the module should be thought of in these areas:

```text
bootstrap-server
  config
  assembly.infrastructure
  assembly.application
  assembly.interfaces
  runtime
```

That conceptual structure prevents the module from collapsing into a single startup file.

---

## Mapping to the current structure

Current files under `modules/bootstrap-server/src/main/scala/chess`:

```text
config/
CorsMiddleware.scala
DesktopMain.scala
EventAssembly.scala
HealthRoutes.scala
Main.scala
ObservableGame.scala
PersistenceAssembly.scala
ServerMain.scala
SharedWiring.scala
```

### What already points in the right direction

- `config/` suggests configuration has already been recognized as a separate concern.
- `PersistenceAssembly.scala` suggests infrastructure assembly is being separated.
- `EventAssembly.scala` suggests event wiring is already treated as a dedicated concern.
- `ServerMain.scala` and `DesktopMain.scala` suggest profile-specific entry points are already emerging.
- `HealthRoutes.scala` suggests operational endpoints are being treated separately from game routes.

Those are good signs. They reflect composition-root thinking rather than just “one main file”.

### Where the current structure is still at risk

#### 1. `SharedWiring.scala` is probably too broad

A file with that name often becomes a catch-all for everything that does not yet have a home.
That is dangerous because composition roots tend to decay into “misc wiring” very quickly.

Risk:

- mixed responsibilities
- hidden dependency direction
- harder migration to more explicit assembly packages later

Recommendation:

- treat `SharedWiring` as transitional only
- progressively split it into infrastructure assembly, application assembly, and interface composition concerns

#### 2. `CorsMiddleware.scala` sitting at the root is a smell

CORS is an interface/runtime concern, not a top-level domain of the module.

Risk:

- operational/transport details become scattered
- more middleware later will create a flat, noisy root package

Recommendation:

- move such concerns under an interface or runtime HTTP area

#### 3. `ObservableGame.scala` may be architecturally misleading

The name suggests backend state may be shaped around observability rather than application event publication.
That can be fine temporarily, but it is risky if it becomes the foundation for WebSocket integration.

Risk:

- WebSocket gets coupled to a special state model
- eventing becomes state-observer coupling instead of a clean event backbone

Recommendation:

- prefer the long-term mental model “application emits events -> event infrastructure distributes them”
- avoid turning “observable game” into the real system backbone if the true backbone should be application event flow

#### 4. `Main.scala`, `ServerMain.scala`, and `DesktopMain.scala` need very clear roles

Multiple entrypoints can be correct, but only if the distinction is explicit.

Risk:

- duplicated startup wiring
- profile logic spread across mains
- drift between desktop and server runtime assembly

Recommendation:

- keep one reusable assembly path
- let different mains only choose runtime profile and launch style
- avoid duplicating actual wiring logic across entrypoints

---

## Recommended target package shape

A good next target for `bootstrap-server` would look conceptually like this:

```text
bootstrap-server
  config
    runtime configuration model
    config loading / validation

  assembly
    infrastructure
      persistence selection
      event infrastructure selection
      resource creation

    application
      command/query/session service wiring

    interfaces
      rest composition
      websocket composition
      middleware composition
      health / metrics composition

  runtime
    server startup
    lifecycle management
    profile-specific launch
```

This is not about producing many files for the sake of ceremony.
It is about making dependency direction and assembly responsibility visible.

---

## Design guidance for the current named files

### `config/`
Should answer:

> What kind of backend process are we starting?

It should own normalized runtime config and validation.
It should not leak transport or persistence construction logic.

### `PersistenceAssembly.scala`
Should answer:

> Where does state live in this deployment?

It should select and build repository implementations and their resources.
It should not perform use-case orchestration.

### `EventAssembly.scala`
Should answer:

> How do state changes become observable across transports and later services?

It should construct the event distribution backbone.
It should not become a second application layer.

### `HealthRoutes.scala`
Should answer:

> How does the process expose operational status?

It should remain operational and lightweight.
It should not couple to business routes.

### `CorsMiddleware.scala`
Should answer:

> What cross-origin rules apply to this deployed backend?

It belongs with transport/runtime HTTP composition, not with business assembly.

### `SharedWiring.scala`
Should answer as little as possible in the long term.

Right now it is likely a transitional composition bucket.
Long term, its responsibility should disappear into clearer assembly areas.

### `Main.scala`
Should likely remain minimal.

It should only delegate into a defined runtime startup path.
If `Main.scala` owns meaningful assembly logic, the composition root will sprawl.

### `ServerMain.scala`
Should own server launch profile concerns.
It should not become the place where every dependency is manually constructed inline.

### `DesktopMain.scala`
If this exists to launch a local desktop-oriented runtime, its role should be profile selection and launch orchestration, not duplicated backend composition.

### `ObservableGame.scala`
This file deserves explicit scrutiny.
If it is acting as an event bridge, that can be valid temporarily.
If it is acting as a parallel state propagation model, that is a long-term architectural risk.

---

## Most important design choice

If there is one design decision to protect now, it is this:

> make event flow part of the bootstrap design from day one

Not Kafka yet.
Not distributed infrastructure yet.
But the shape must already be:

- application emits events
- event infrastructure distributes them
- WebSocket consumes them
- later Kafka can bridge them

That one decision supports:

- live Web UI updates
- reactive streams later
- cleaner microservice evolution
- better observability
- more stable transport boundaries

If that shape is correct, a lot of later work becomes incremental instead of architectural rework.

---

## Final compact blueprint

`bootstrap-server` should:

- load config
- choose persistence, event, and runtime profile
- create resources
- wire repositories and event infrastructure
- wire application services
- create REST and WebSocket adapters
- mount external endpoints
- start the backend process

`bootstrap-server` must not:

- own business rules
- bypass application boundaries
- let WebSocket become a second backend
- couple transports directly to persistence
- absorb route or repository logic

That gives the backend a clean and durable composition root.

---

## Recommendation for next refactoring step

Before adding more runtime features, the next structural cleanup should be:

1. make `Main` minimal
2. reduce `SharedWiring` into explicit assembly areas
3. treat event flow as a first-class assembly concern
4. move HTTP/runtime concerns like CORS closer to interface composition
5. ensure REST and WebSocket both depend on application/event boundaries, not on persistence directly

That sequence will keep the module aligned with both the current Http4s/Web UI milestone and the later microservices direction.
