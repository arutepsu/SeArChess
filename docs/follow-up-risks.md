# Follow-up Risks

## Pipeline provider reliability

The pipeline uses the configured AI provider.
Current default is StubAIReviewProvider.
When a real provider is added, pipeline runtime may depend on network/API/model availability.

Mitigations:
- keep stub as default
- allow PERF_AI_PROVIDER=stub in CI
- fail clearly if provider fails
- consider adding --no-ai later

## Intermediate artifacts

The current pipeline prints final Markdown only.
For CI and assignment evidence, we may later want stored artifacts:

- baseline-report.json
- optimized-report.json
- comparison-report.json
- ai-review.json
- performance-review.md

Potential future enhancement:
- add pipeline artifact-output mode
- do not implement this now

## AI provider status

The current AI layer is a provider boundary with a stub provider.
Do not claim live AI-assisted analysis until a real provider is implemented.

Current wording:
- "AI review integration boundary"
- "stub provider"
- "provider-selection mechanism"

Future wording after real provider:
- "AI-assisted performance review generation"
