# adapter-rest-contract Blueprint

## 1. Purpose

`adapter-rest-contract` is the REST-facing contract layer of the backend.

Its job is to define the stable data contract exchanged at the HTTP boundary, independent of the specific REST engine that exposes it.

This module exists so that:
- the backend has an explicit transport contract,
- the Web UI has a stable integration surface,
- REST DTO evolution is separated from domain and application evolution,
- server implementations can change without rewriting the public contract model.

Conceptually, this module is the backend's **transport contract package** for REST.

It is not:
- a business module,
- an application orchestration module,
- a route implementation module,
- a persistence module,
- a place for framework-specific HTTP concerns.

---

## 2. Why this module matters

This module is more important than it first appears.

In a modular architecture, transport contracts are often treated as small helper classes. That is a mistake. Here, `adapter-rest-contract` should become the **stable public contract surface** between:
- backend and Web UI,
- backend and integration tests,
- backend and any future alternate HTTP adapter,
- potentially multiple backend deployments exposing the same API shape.

That means this module protects the system from a common architectural failure:

**leaking domain internals directly into REST.**

Once domain objects or application internals become the HTTP contract, refactoring becomes expensive and unsafe. A dedicated contract module prevents that coupling.

---

## 3. Long-term role

Long-term, `adapter-rest-contract` should serve as the canonical REST schema layer.

It should contain:
- request DTOs,
- response DTOs,
- error DTOs,
- transport-facing validation shapes,
- serialization-friendly API models,
- versionable contract types,
- mapping boundaries between application outputs and REST payloads.

It should not contain:
- domain rules,
- chess move validation rules,
- application workflow orchestration,
- persistence knowledge,
- framework route definitions,
- endpoint mounting,
- server startup logic,
- transport engine specifics from http4s or any other framework.

---

## 4. Current structure

Current package root:

`chess.adapter.rest.contract`

Current substructure:

- `dto`
- `mapper`

Current DTO files:
- `CreateSessionRequest.scala`
- `CreateSessionResponse.scala`
- `ErrorResponse.scala`
- `GameResponse.scala`
- `SessionResponse.scala`
- `SubmitMoveRequest.scala`
- `SubmitMoveResponse.scala`

Current mapper files:
- `GameMapper.scala`
- `MoveMapper.scala`
- `SessionMapper.scala`

This is already a sensible split.

At a conceptual level:
- `dto` defines **what crosses the wire**.
- `mapper` defines **how internal representations are translated to and from that wire contract**.

That separation should stay explicit.

---

## 5. Inputs to adapter-rest-contract

This module should consume only a narrow class of inputs.

### A. Public application-facing data

The module may depend on stable application outputs or command/result models that need to be exposed externally.

Examples:
- session creation result data,
- game state query result data,
- move submission result data,
- application-level error categories.

Important:
this should be **already application-shaped**, not domain internals leaking upward.

### B. Serialization concerns

The module may define or align with serialization-safe shapes.

Examples:
- optional fields,
- nested resource structures,
- enum/string representations,
- wire-safe representations for IDs, moves, status, metadata.

### C. Transport validation requirements

The module may encode transport-facing validation expectations.

Examples:
- required request fields,
- field naming rules,
- shape-level validation constraints,
- input shape restrictions before deeper application processing.

Important:
this is **shape validation**, not business validation.

---

## 6. Outputs of adapter-rest-contract

Conceptually, this module produces one thing:

**A stable REST contract model.**

That includes:
- request payload shapes,
- response payload shapes,
- error payload shapes,
- mapping boundaries used by HTTP adapters.

The output is not “routes.”
The output is not “HTTP handling.”
The output is not “JSON codecs tied to a server engine.”

It is the **data contract** that a REST adapter exposes.

---

## 7. Internal responsibility split

I would define two primary responsibility areas inside this module.

### 7.1 `dto`

Purpose:
- define request and response models at the REST boundary.

Owns:
- create-session request/response shapes,
- move submission request/response shapes,
- game/session projection responses,
- structured API error responses,
- any future pagination, metadata, or versioned response wrappers.

Should optimize for:
- clarity,
- stability,
- client usability,
- serialization friendliness,
- explicitness over convenience.

Should not own:
- mapping logic,
- route logic,
- business validation,
- framework annotations that couple it to one transport engine unless deliberately isolated.

### 7.2 `mapper`

Purpose:
- translate between application-facing models and REST DTOs.

Owns:
- mapping from application outputs to response DTOs,
- mapping from request DTOs to application command inputs,
- conversion of application errors into contract-level error payloads,
- normalization of field naming or shape differences between application and REST.

Should not own:
- business decisions,
- side effects,
- route branching,
- persistence access,
- orchestration,
- endpoint behavior.

The mapper layer is a boundary translator, not a service layer.

---

## 8. What should live in `dto`

The `dto` package should be treated as the public schema area.

### Good contents

- resource-shaped request models,
- resource-shaped response models,
- nested substructures needed for API readability,
- wire-safe identifiers,
- explicit error response schema,
- transport metadata wrappers if needed later,
- version-specific DTO packages if the API evolves.

### Bad contents

- domain entities,
- domain value objects reused directly for convenience,
- repository records,
- application service implementations,
- framework route logic,
- direct dependency on persistence or eventing.

### Design rule

A DTO should answer this question:

**What should a client send or receive over REST?**

It should not answer:
- how the application works internally,
- how the domain stores or computes state,
- how the server framework processes the request.

---

## 9. What should live in `mapper`

The `mapper` package should be treated as the anti-corruption layer between the REST contract and the inner system.

### Good contents

- request-to-command transformation,
- result-to-response transformation,
- error-to-error-response transformation,
- field adaptation where external naming and internal naming differ,
- flattening or nesting logic needed for contract clarity.

### Bad contents

- calling repositories,
- executing use cases,
- deciding whether a move is legal,
- fetching session state,
- branching based on HTTP framework behavior,
- lifecycle management,
- subscription or event-stream logic.

### Design rule

A mapper should answer this question:

**How does a transport contract shape correspond to an application-facing shape?**

It should not answer:

**What should happen in the system?**

That is application territory.

---

## 10. Contract composition model

The intended flow should look like this:

```text
Client
  -> REST adapter (http4s routes)
    -> request DTO
    -> mapper
    -> application command/query model
    -> application service
    -> application result
    -> mapper
    -> response DTO
  -> Client
```

And for failures:

```text
application/service error
  -> mapper
  -> ErrorResponse DTO
  -> REST adapter sets HTTP status code
```

This is the right separation because:
- routes own HTTP behavior,
- mappers own shape translation,
- application owns use-case behavior,
- domain owns business rules.

---

## 11. Relationship to other modules

### Depends on

`adapter-rest-contract` may depend on:
- stable application-facing result/command models,
- shared serialization helpers if intentionally extracted,
- minimal common model contracts when truly necessary.

### Must not depend on

It should not depend on:
- `bootstrap-server`,
- persistence adapters,
- websocket adapter,
- route implementation modules,
- framework server startup code.

### Is consumed by

It should be consumed by:
- `adapter-rest-http4s`,
- Web UI client generation or manual client code,
- API contract tests,
- possibly future alternate HTTP adapters.

This dependency direction is important.

The contract module should sit **below concrete REST adapters** and **above transport-agnostic application outputs**.

---

## 12. What this module must not become

This is where drift usually starts.

### A. Not a mini application layer

Bad sign:
- mappers start invoking services,
- request handling logic grows inside the contract module,
- DTOs begin encoding workflow decisions.

### B. Not a domain mirror

Bad sign:
- domain objects are re-exported as API objects with little or no translation,
- REST schema changes are blocked because domain types are reused directly.

### C. Not framework glue

Bad sign:
- route-specific decoding behavior lives here,
- http4s-specific route concerns or status handling are embedded into DTO/mappers.

### D. Not a dumping ground for “shared” types

Bad sign:
- unrelated helper classes get parked here because they are used by HTTP code,
- generic utilities accumulate without a clear contract purpose.

This module should stay narrow and disciplined.

---

## 13. Versioning role

A major architectural reason to keep this module clean is API evolution.

Over time, you may need:
- additional response fields,
- renamed transport fields,
- deprecated request structures,
- alternate client needs,
- multiple API versions.

That becomes manageable if this module is the explicit REST schema layer.

A future versioning structure could look conceptually like:
- `contract.v1.dto`
- `contract.v1.mapper`
- `contract.v2.dto`
- `contract.v2.mapper`

You do not need that now.
But the module should be clean enough that versioning can be introduced without tearing apart the whole server.

---

## 14. Suggested responsibility of the current files

Based on file names alone, the current intent appears reasonable.

### DTOs

- `CreateSessionRequest`  
  Transport shape for session creation input.

- `CreateSessionResponse`  
  Transport shape for successful session creation output.

- `ErrorResponse`  
  Canonical REST error payload shape.

- `GameResponse`  
  Transport projection of game state or game details.

- `SessionResponse`  
  Transport projection of session state/details.

- `SubmitMoveRequest`  
  Transport shape for move submission input.

- `SubmitMoveResponse`  
  Transport shape for move submission result.

### Mappers

- `GameMapper`  
  Boundary translation between application game projections/results and `GameResponse`.

- `MoveMapper`  
  Boundary translation between move-related request/response shapes and application move inputs/results.

- `SessionMapper`  
  Boundary translation between session-related application data and session DTOs.

That split is good as long as these remain pure translators.

---

## 15. Validation boundary

One subtle but important point:

`adapter-rest-contract` may express **transport validation**, but it should not absorb **business validation**.

### Transport validation examples

Good here:
- missing required fields,
- malformed structure,
- invalid primitive format,
- unsupported enum string at the wire level.

### Business validation examples

Not here:
- illegal chess move,
- invalid turn order,
- session not joinable due to business state,
- move rejected because game is finished.

Those belong deeper in application/domain layers.

This distinction prevents the contract layer from becoming semantically overloaded.

---

## 16. Error modeling role

`ErrorResponse` is especially important.

This module should become the place where the REST API expresses errors consistently.

That means the contract should support:
- stable machine-readable error categories,
- human-readable message fields,
- optional detail fields when useful,
- future extensibility for validation errors or correlation IDs.

But the contract module should not decide HTTP status codes by itself unless that mapping is deliberately abstracted.

A healthy split is:
- contract defines error payload shape,
- REST adapter decides HTTP status mapping,
- mapper translates application error semantics to contract error payload.

---

## 17. Recommended architectural rules

I would document these rules explicitly.

### Rule 1

`adapter-rest-contract` defines REST data contracts, not business flows.

### Rule 2

DTOs must be transport-oriented, not domain-oriented.

### Rule 3

Mappers translate shapes; they do not orchestrate use cases.

### Rule 4

Concrete REST adapters may depend on this module; this module must not depend on concrete REST adapters.

### Rule 5

Business validation stays in application/domain, even if some request fields look similar.

### Rule 6

Contract stability matters more than internal model convenience.

### Rule 7

When in doubt, prefer explicit DTOs over exposing internal objects directly.

These rules will matter a lot once the Web UI starts evolving independently.

---

## 18. Recommended conceptual substructure

Even if you keep the current layout, think of it this way internally:

- `dto`  
  public REST schema

- `mapper`  
  translation boundary

Later, if needed, you may add:

- `error`  
  richer error contract definitions

- `validation`  
  transport-shape validation helpers

- `codec`  
  serialization-specific support if you deliberately isolate it

But do not add these just to look clean. Add them only when the current package becomes overloaded.

---

## 19. Most important design choice

If I had to name the single most important design choice for this module, it would be this:

**Treat REST contract models as stable external products, not as accidental wrappers around internal objects.**

That mindset changes everything:
- you design DTOs for clients,
- you allow internal refactoring safely,
- you keep Web UI evolution decoupled,
- you create a clean seam for later API versioning.

Without that discipline, the REST layer becomes tightly coupled to internal churn.

---

## 20. Final compact version

`adapter-rest-contract` is the REST-facing contract layer.

It should:
- define request and response DTOs,
- define structured error payloads,
- represent transport-facing validation shapes,
- provide mapping boundaries between application models and REST payloads,
- remain independent of the specific REST engine.

It must not:
- contain domain logic,
- orchestrate application use cases,
- implement route behavior,
- couple directly to persistence,
- absorb framework-specific HTTP handling.

`dto` should define what crosses the wire.  
`mapper` should define how internal models are translated to and from that wire contract.

That gives a stable backend-to-Web-UI contract surface and a clean boundary for future API growth.
