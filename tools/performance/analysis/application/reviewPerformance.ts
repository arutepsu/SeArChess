import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';
import type { AIReview, AIReviewProvider, AIReviewRequest } from '../ai/aiReviewModels';
import { runAIReview } from '../ai/aiReviewService';

export async function reviewSingle(
  report: PerformanceReport,
  provider: AIReviewProvider,
  context?: AIReviewRequest['context'],
): Promise<AIReview> {
  return runAIReview({ mode: 'single-run', performanceReport: report, context }, provider);
}

export async function reviewComparison(
  report: PerformanceComparisonReport,
  provider: AIReviewProvider,
  context?: AIReviewRequest['context'],
): Promise<AIReview> {
  return runAIReview({ mode: 'comparison', comparisonReport: report, context }, provider);
}
