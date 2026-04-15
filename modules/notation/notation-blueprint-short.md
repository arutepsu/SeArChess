# notation Blueprint

## Purpose

`notation` is the format and parsing module.

It should:
- define stable notation-facing APIs
- support chess notation formats such as PGN and FEN
- support JSON representations if needed for notation exchange
- parse and render notation independently from transport or UI
- allow multiple parser implementations behind stable boundaries

Its role is notation handling, not game orchestration.

## It is not

It must not become:
- the application layer
- a transport adapter
- a persistence module
- a UI module
- a place for runtime wiring

It should describe and process notation, not run the system.

## Core rule

Notation should be exposed through stable APIs, while concrete parsing techniques stay replaceable.

Correct model:

client / application  
-> notation api  
-> format module (`pgn`, `fen`, `json`)  
-> selected parser implementation (`parser combinator`, `fastparse`, `regex`)

That means parser technology should not leak into the rest of the system.

## Suggested internal split

- `notation.api`  
  Stable notation-facing interfaces and shared result/error model.

- `notation.pgn`  
  PGN model, parsing, rendering, and parser variants.

- `notation.fen`  
  FEN model, parsing, rendering, and parser variants.

- `notation.json`  
  JSON notation or serialization-facing representation if needed.

## What `notation.api` should own

It should define:
- stable parser/renderer boundaries
- shared parse result and error model
- common abstractions used by PGN and FEN
- format-independent contracts

It should not contain:
- format-specific grammar details
- transport DTOs
- application orchestration

## What `notation.pgn` and `notation.fen` should own

They should own:
- format-specific grammar and model
- parsing and rendering for that notation
- validation at notation syntax level
- selection between parser implementations

They should not own:
- HTTP concerns
- repository logic
- UI formatting logic
- broader game workflow rules

## Parser implementation rule

Supporting multiple parser techniques is fine:

- parser combinator
- fastparse
- regex

But they should be treated as interchangeable implementations behind a stable format boundary.

So:
- the rest of the system should depend on the PGN/FEN API
- not on whether the parser is regex, fastparse, or combinator-based

That keeps experimentation possible without polluting the architecture.

## Main architectural rules

1. `notation.api` defines stable boundaries; concrete parser techniques stay behind them.
2. PGN and FEN are separate format concerns and should stay clearly separated.
3. Parser implementation choice must not leak into application or transport modules.
4. Notation syntax validation belongs here; business use-case orchestration does not.
5. `notation` may be used by application or adapters, but it should remain independent of them.

## Why this matters

This keeps the system clean:

- `notation.api` = stable notation boundary
- `notation.pgn` / `notation.fen` / `notation.json` = format-specific logic
- parser combinator / fastparse / regex = replaceable parsing techniques
- `application` = use-case orchestration
- adapters = transport and UI

If this boundary stays clean, you can evolve notation support and compare parser strategies without disturbing the rest of the system.
