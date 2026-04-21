# adapter-gui Blueprint

## Purpose

`adapter-gui` is the desktop graphical user interface adapter.

It should:
- present the game to the user
- capture user interactions
- call application services or controller boundaries
- render current state and updates

Its role is user interaction, not business execution.

## It is not

It must not become:
- a business logic layer
- a persistence layer
- a second source of truth for game rules
- a replacement for the application layer

The backend or application layer remains authoritative.

## Core rule

GUI is just another client of the application layer.

Correct model:

GUI  
-> controller / application boundary  
-> application  
-> domain

That means GUI code should not mutate core state directly.

## What it should own

- scenes, windows, widgets, and presentation logic
- user interaction flow
- UI state needed for rendering
- translation of user actions into application calls

## What it should not own

- chess rules
- repository access
- persistence decisions
- transport contracts
- event distribution backbone

## Main architectural rules

1. `adapter-gui` calls application or controller boundaries, not repositories.
2. Game rules stay in domain/application, not in UI code.
3. GUI state is for presentation, not business authority.
4. The same backend rules must apply to GUI, TUI, AI, and Web UI.
5. `adapter-gui` should stay replaceable without changing application logic.

## Why this matters

This keeps the system clean:

- `adapter-gui` = desktop presentation
- `application` = use cases
- `domain` = rules
- `game-service` or app bootstrap = runtime assembly

If this boundary stays clean, the GUI remains testable, replaceable, and consistent with every other client.
