import { createAIReviewProvider } from '../ai/aiReviewProviderFactory';
import { analyze } from './analyzePerformance';
import { compare } from './comparePerformance';
import { renderPerformanceReview } from './renderPerformanceReview';
import { reviewComparison } from './reviewPerformance';

export interface PerformancePipelineInput {
  baselineInput: unknown;
  optimizedInput: unknown;
  title?: string;
}

export async function runPerformancePipeline(input: PerformancePipelineInput): Promise<string> {
  const baselineReport = analyze(input.baselineInput);
  const optimizedReport = analyze(input.optimizedInput);
  const comparisonReport = compare(baselineReport, optimizedReport);
  const provider = createAIReviewProvider();
  const aiReview = await reviewComparison(comparisonReport, provider);

  return renderPerformanceReview({
    performanceReport: optimizedReport,
    comparisonReport,
    aiReview,
    title: input.title,
  });
}
