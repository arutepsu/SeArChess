import type { PerformanceReport, PerformanceComparisonReport } from '../domain/models';
import type {
  AIReview,
  AIReviewProvider,
  AIReviewRequest,
  ReviewInput,
  ReviewReader,
  ReviewReport,
} from '../ai/aiReviewModels';
import { readReviewInput, runAIReview } from '../ai/aiReviewService';
import {
  saveStructuredReviewArtifacts,
  type StructuredReviewArtifactPaths,
} from './artifacts/structuredReviewArtifacts';

export interface GenerateStructuredReviewOptions {
  outputDir?: string;
}

export interface GenerateStructuredReviewResult {
  report: ReviewReport;
  saved: boolean;
  paths?: StructuredReviewArtifactPaths;
}

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

export async function reviewReportSuite(
  reports: PerformanceReport[],
  provider: AIReviewProvider,
  context?: AIReviewRequest['context'],
): Promise<AIReview> {
  return runAIReview({ mode: 'report-suite', performanceReports: reports, context }, provider);
}

export async function readStructuredReview(
  input: ReviewInput,
  reader: ReviewReader,
): Promise<ReviewReport> {
  return readReviewInput(input, reader);
}

export async function generateStructuredReview(
  input: ReviewInput,
  reader: ReviewReader,
  options: GenerateStructuredReviewOptions = {},
): Promise<GenerateStructuredReviewResult> {
  const report = await readStructuredReview(input, reader);
  if (!options.outputDir) {
    return { report, saved: false };
  }

  const paths = saveStructuredReviewArtifacts(report, options.outputDir);
  return { report, saved: true, paths };
}
