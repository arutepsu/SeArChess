# Game Service AI Adapters

This module contains Game-owned implementations of the
`AiMoveSuggestionClient` port.

## Runtime Shape

The canonical runtime client is `RemoteAiMoveSuggestionClient`. It calls the
standalone Python AI service over HTTP and returns only a move suggestion. Game
Service still owns legal move generation, final validation, move application,
session lifecycle, persistence, and events.

`LocalDeterministicAiClient` is a dev/test fallback. It is selected only when
`AI_PROVIDER_MODE=local` or `local-deterministic` is configured, and should not
be treated as the production-like runtime path.

## Test Hooks

`AI_REMOTE_TEST_MODE` is a local/dev verification hook. When set, it is sent to
the Python AI service as `metadata.testMode` so end-to-end tests can prove Game
rejects illegal or malformed provider output. Leave it unset for normal runtime.
