# adapter-rest-contract

## Purpose

`adapter-rest-contract` defines the external REST API schema.

It should:
- define request and response DTOs
- define error response shapes
- provide a stable contract between backend and clients

It is the public HTTP API shape, not the Game Service implementation.

## It is not

It must not contain:
- business logic
- application orchestration
- HTTP framework code
- persistence logic
- mapping to or from Game core/domain types

## Structure

- `dto/` -> request and response models

Mapping between Game Service internals and these DTOs lives outside this module,
currently in `adapter-rest-http4s`.

## Core Rule

This module defines what the API looks like, not how it works.

## Main Architectural Rules

1. DTOs are the only public shapes exposed to clients.
2. Domain/application models must not leak into the API.
3. Contract must be independent of http4s or any framework.
4. Contract must be independent of Game core/domain internals.
5. Changes here are API changes; treat them carefully.

## Why This Matters

This keeps the system clean:

- `adapter-rest-contract` = stable API schema
- `adapter-rest-http4s` = HTTP implementation and internal mapping
- `game-core` = Game Service use cases

This separation allows independent frontend/backend evolution and future client
generation without importing Game Service internals.
