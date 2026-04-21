# Game Service AI Adapters

This module contains Game-owned implementations of the
`AiMoveSuggestionClient` port.

The internal HTTP DTOs and JSON codec live in `modules/ai-contract`. This
module owns only Game Service adapter behavior: mapping Game state into the
contract, sending the synchronous HTTP request, and converting the response back
to the application AI port.

## Runtime Shape

The canonical runtime client is `RemoteAiMoveSuggestionClient`. It calls the
configured internal AI Service over HTTP and returns only a move suggestion. Game
Service still owns legal move generation, final validation, move application,
session lifecycle, persistence, and events.

`LocalDeterministicAiClient` is a dev/test fallback. It is selected only when
`AI_PROVIDER_MODE=local` or `local-deterministic` is configured, and should not
be treated as the production-like runtime path.

## Test Hooks

`AI_REMOTE_TEST_MODE` is a local/dev verification hook. When set, the Game
adapter sends it as the `X-Searchess-AI-Test-Mode` HTTP header so end-to-end
tests can prove Game rejects illegal or malformed provider output without
polluting the inference request body. Leave it unset for normal runtime.
