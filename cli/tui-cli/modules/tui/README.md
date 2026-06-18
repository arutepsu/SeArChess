# adapter-tui Blueprint

## Purpose

`adapter-tui` is the text-based user interface adapter.

It should:
- present the game in a terminal-friendly form
- read user input
- translate input into application or controller calls
- render current state and feedback to the user

Its role is interaction through text, not business execution.

## It is not

It must not become:
- a business logic layer
- a persistence layer
- a second source of truth for game rules
- a replacement for the application layer

The application/domain remains authoritative.

## Core rule

TUI is just another client of the application layer.

Correct model:

TUI  
-> controller / application boundary  
-> application  
-> domain

That means TUI code should not mutate core state directly.

## What it should own

- command parsing at UI level
- prompt and text rendering
- terminal interaction flow
- translation of user commands into application calls

## What it should not own

- chess rules
- repository access
- persistence decisions
- transport contracts
- event distribution backbone

## Main architectural rules

1. `adapter-tui` calls application or controller boundaries, not repositories.
2. Game rules stay in domain/application, not in TUI code.
3. TUI state is for interaction flow, not business authority.
4. The same backend rules must apply to TUI, GUI, AI, and Web UI.
5. `adapter-tui` should stay replaceable without changing application logic.

## Why this matters

This keeps the system clean:

- `adapter-tui` = text-based presentation
- `application` = use cases
- `domain` = rules
- bootstrap/app entry = runtime assembly

If this boundary stays clean, the TUI remains simple, testable, and consistent with every other client.
