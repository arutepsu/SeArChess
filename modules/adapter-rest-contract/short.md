# adapter-rest-contract Blueprint

## Purpose

`adapter-rest-contract` defines the external REST API schema.

It should:
- define request and response DTOs
- define error response shapes
- provide a stable contract between backend and clients (Web UI, others)

It is the public API surface of the backend.

## It is not

It must not contain:
- business logic
- application orchestration
- HTTP framework code
- persistence logic

## Structure

- `dto/` → request and response models  
- `mapper/` → mapping between application/domain and DTOs (schema-focused only)

## Core rule

This module defines *what the API looks like*, not *how it works*.

## Main architectural rules

1. DTOs are the only public shapes exposed to clients.
2. Domain/application models must not leak into the API.
3. Contract must be independent of http4s or any framework.
4. Changes here are API changes — treat them carefully.

## Why this matters

This keeps the system clean:

- `adapter-rest-contract` = stable API schema  
- `adapter-rest-http4s` = HTTP implementation  
- `application` = use cases  

This separation allows:
- independent frontend/backend evolution
- easier testing
- future replacement of transport layer without breaking the API
