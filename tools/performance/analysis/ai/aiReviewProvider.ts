import type { AIReview, AIReviewProvider } from './aiReviewModels';

export class StubAIReviewProvider implements AIReviewProvider {
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
}
