import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';

export interface AIReviewRequest {
  mode: 'single-run' | 'comparison';
  performanceReport?: PerformanceReport;
  comparisonReport?: PerformanceComparisonReport;
  context?: {
    systemName?: string;
    scenarioName?: string;
    testType?: string;
    knownLimitations?: string[];
  };
}

export interface AIReview {
  executiveSummary: string;
  bottleneckExplanation: string;
  improvementAssessment: string;
  risks: string[];
  suggestedNextActions: string[];
  missingEvidence: string[];
  confidenceCommentary: string;
}

export interface AIReviewProvider {
  review(prompt: string): Promise<AIReview>;
}
