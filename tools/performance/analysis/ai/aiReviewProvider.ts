import type { AIReview, AIReviewProvider, ReviewInput, ReviewReader, ReviewReport } from './aiReviewModels';

export class StubAIReviewProvider implements AIReviewProvider, ReviewReader {
  async review(_prompt: string): Promise<AIReview> {
    return {
      executiveSummary:       'Stub review: system shows signs of a performance constraint based on the provided metrics.',
      bottleneckExplanation:  'The deterministic classifier identified the bottleneck using rule-based thresholds applied to system metrics.',
      improvementAssessment:  'No real improvement data evaluated; this is a stub response.',
      risks:                  ['Stub: insufficient data to identify real risks'],
      suggestedNextActions:   ['Stub: run profiling to gather more signal', 'Stub: add tracing to identify hot paths'],
      missingEvidence:        ['Stub: database query latency breakdown', 'Stub: GC pause data'],
      confidenceCommentary:   'This is a stub provider. Replace with a real AI provider for production use.',
    };
  }

  async readReview(input: ReviewInput): Promise<ReviewReport> {
    const moduleName = input.moduleName ?? 'performance-analysis';
    const hasNotes = (input.notes ?? []).length > 0;
    const hasReviewText = (input.reviewText ?? '').trim().length > 0;

    return {
      summary: `Stub structured review for ${moduleName}. No external AI provider was called.`,
      findings: [
        {
          severity: 'info',
          category: 'architecture',
          location: moduleName,
          message: 'Review reading is wired through the existing AI analysis boundary.',
          reasoning: 'The input is accepted by the performance analysis AI layer and converted into a structured report.',
          suggestion: 'Keep future real-provider parsing behind the ReviewReader interface.',
        },
        {
          severity: hasNotes || hasReviewText ? 'info' : 'warning',
          category: 'testing',
          message: hasNotes || hasReviewText
            ? 'Review input included user-provided context.'
            : 'No review notes or review text were provided.',
          reasoning: hasNotes || hasReviewText
            ? 'The deterministic reader can preserve that context for future richer analysis.'
            : 'The current report is produced from minimal input, so it cannot comment on actual test evidence.',
          suggestion: 'Pass concise review notes or pasted review text when invoking this capability.',
        },
        {
          severity: 'info',
          category: 'maintainability',
          location: 'StubAIReviewProvider',
          message: 'Review reading currently uses deterministic stub output.',
          reasoning: 'This keeps the first step testable and avoids new external API behavior.',
          suggestion: 'Add real parsing only after the structured report contract is stable.',
        },
      ],
      suggestedNextSteps: [
        'Render the structured review report into markdown for saved artifacts.',
        'Add explicit review input examples for project, game, and test reviews.',
        'Connect a real provider only through the existing ReviewReader boundary.',
      ],
    };
  }
}
