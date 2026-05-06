import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';

export interface AIReviewRequest {
  mode: 'single-run' | 'comparison' | 'report-suite';
  performanceReport?: PerformanceReport;
  performanceReports?: PerformanceReport[];
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

export type ReviewSeverity = 'info' | 'warning' | 'critical';

export type ReviewCategory =
  | 'architecture'
  | 'testing'
  | 'performance'
  | 'coupling'
  | 'maintainability'
  | 'unknown';

export interface ReviewInput {
  moduleName?: string;
  userQuestion?: string;
  notes?: string[];
  reviewText?: string;
}

export interface ReviewFinding {
  severity: ReviewSeverity;
  category: ReviewCategory;
  location?: string;
  message: string;
  reasoning: string;
  suggestion: string;
}

export interface ReviewReport {
  summary: string;
  findings: ReviewFinding[];
  suggestedNextSteps: string[];
}

export interface ReviewReader {
  readReview(input: ReviewInput): Promise<ReviewReport>;
}
