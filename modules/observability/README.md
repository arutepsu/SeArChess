# observability

Scope: dependency-free utilities that are genuinely service-neutral.

`StructuredLog` lives here so Game, AI, and History can emit the same JSON-line
shape without depending on another service's contract module.

This module must remain free of service routes, domain rules, persistence, and
cross-service protocol decisions.
