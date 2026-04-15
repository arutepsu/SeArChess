# web-ui Blueprint

## Purpose

`web-ui` is the client-facing user interface.

It should:
- present the game to the user
- call backend APIs through the REST contract
- render backend state and user interactions
- stay separate from backend business logic

Its role is user interaction, not rule execution.

## It is not

It must not become:
- a business logic layer
- a second source of truth for game rules
- a persistence layer
- a replacement for backend application services

The backend remains authoritative.

## Core rule

`web-ui` should consume stable backend contracts.

Correct model:

web-ui  
-> REST contract  
-> REST adapter  
-> application  
-> domain

And for live updates:

web-ui  
-> WebSocket contract/messages  
-> backend event flow

That means the UI should not invent its own interpretation of backend state.

## What it should own

- pages, views, and UI components
- user interaction flow
- client-side state needed for presentation
- API client integration based on backend contracts
- rendering of backend responses and live updates

## What it should not own

- chess rules
- move legality logic as source of truth
- backend workflow orchestration
- direct database access
- server-only validation rules

## Main architectural rules

1. `web-ui` depends on backend contracts, not backend internals.
2. API request and response shapes should stay aligned with `adapter-rest-contract`.
3. Backend remains the source of truth for validation and state changes.
4. UI state is for presentation and interaction, not business authority.
5. Live updates should come from shared backend event flow, not ad hoc polling logic everywhere.

## Contracts relation

The key relationship is:

- `adapter-rest-contract` = stable API schema
- `web-ui` = consumer of that schema

This matters because:
- frontend and backend can evolve more safely
- API drift is reduced
- testing becomes easier
- future clients can reuse the same contract model

## Why this matters

This keeps the system clean:

- `web-ui` = presentation and interaction
- `adapter-rest-contract` = backend API contract
- `application` = use cases
- backend = source of truth

If this boundary stays clean, the UI remains replaceable and the backend stays authoritative.
