import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createAIReviewProvider } from '../../ai/aiReviewProviderFactory';
import { reviewReportSuite, reviewSingle } from '../../application/reviewPerformance';
import { renderPerformanceReview } from '../../application/renderPerformanceReview';
import type { AIReview } from '../../ai/aiReviewModels';
import type { PerformanceReport } from '../../domain/models';
import { validatePerformanceReport } from '../../validation/validatePerformanceReport';
import { validateAIReview } from '../../validation/validateAIReview';
import type { PerformanceCliConfig } from '../config';
import type { RunHistoryItem } from './runHistory';

export interface AIReviewArtifactPaths {
  jsonPath: string;
  markdownPath: string;
}

export interface ReviewableRunBundle {
  runId: string;
  runPath: string;
  tool: 'k6' | 'unknown';
  kind: 'single' | 'suite' | 'unknown';
  reportJsonPaths: string[];
  reportMarkdownPaths: string[];
  suiteMarkdownPath?: string;
}

export interface GenerateAIReviewResult {
  review: AIReview;
  paths: AIReviewArtifactPaths;
  bundle: ReviewableRunBundle;
}

export const AI_REVIEW_DISABLED_MESSAGE = 'AI review is disabled. Enable it in Settings or performance.config.json.';

export function buildAIReviewArtifactPaths(runPath: string, kind: ReviewableRunBundle['kind'] = 'single'): AIReviewArtifactPaths {
  const prefix = kind === 'suite' ? 'ai_suite_review' : 'ai_review';
  return {
    jsonPath: join(runPath, `${prefix}.json`),
    markdownPath: join(runPath, `${prefix}.md`),
  };
}

export function selectAIReviewMarkdown(item: RunHistoryItem): string | undefined {
  const suitePath = buildAIReviewArtifactPaths(item.path, 'suite').markdownPath;
  const singlePath = buildAIReviewArtifactPaths(item.path, 'single').markdownPath;
  if (item.kind === 'k6-suite' && existsSync(suitePath)) return suitePath;
  if (item.kind === 'k6-single' && existsSync(singlePath)) return singlePath;
  if (existsSync(suitePath)) return suitePath;
  return existsSync(singlePath) ? singlePath : undefined;
}

function sortedEntries(path: string, predicate: (entry: string) => boolean): string[] {
  return readdirSync(path)
    .filter(predicate)
    .sort();
}

function isK6DeterministicReportJson(entry: string): boolean {
  return /^k6_.*_report\.json$/.test(entry);
}

function isK6DeterministicReportMarkdown(entry: string): boolean {
  return /^k6_.*_report\.md$/.test(entry);
}

export function buildReviewableRunBundle(item: RunHistoryItem): ReviewableRunBundle {
  const reportJsonPaths = sortedEntries(item.path, isK6DeterministicReportJson)
    .map((entry) => join(item.path, entry));
  const reportMarkdownPaths = sortedEntries(item.path, isK6DeterministicReportMarkdown)
    .map((entry) => join(item.path, entry));
  const suiteMarkdownPath = existsSync(join(item.path, 'k6_suite_report.md'))
    ? join(item.path, 'k6_suite_report.md')
    : undefined;
  const tool = item.kind === 'k6-single' || item.kind === 'k6-suite' ? 'k6' : 'unknown';
  const kind = item.kind === 'k6-suite'
    ? 'suite'
    : item.kind === 'k6-single'
      ? 'single'
      : 'unknown';

  return {
    runId: item.runId,
    runPath: item.path,
    tool,
    kind,
    reportJsonPaths,
    reportMarkdownPaths,
    suiteMarkdownPath,
  };
}

function providerEnv(config: PerformanceCliConfig): NodeJS.ProcessEnv {
  return {
    ...process.env,
    PERF_AI_PROVIDER: config.ai?.provider ?? 'stub',
  };
}

export async function generateAIReviewForRun(
  item: RunHistoryItem,
  config: PerformanceCliConfig,
): Promise<GenerateAIReviewResult> {
  if (config.ai?.enabled !== true) {
    throw new Error(AI_REVIEW_DISABLED_MESSAGE);
  }

  const provider = createAIReviewProvider(providerEnv(config));
  const bundle = buildReviewableRunBundle(item);
  if (bundle.kind === 'unknown') {
    throw new Error('Cannot generate AI review for unknown run type.');
  }
  if (bundle.reportJsonPaths.length === 0) {
    throw new Error('No deterministic report JSON found for this run.');
  }

  const reports = bundle.reportJsonPaths.map((reportPath) => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(reportPath, 'utf-8'));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      throw new Error(`Invalid deterministic report JSON ${reportPath}: ${message}`);
    }
    const reportErrors = validatePerformanceReport(parsed);
    if (reportErrors.length > 0) {
      throw new Error(`Invalid deterministic report ${reportPath}: ${reportErrors.join('; ')}`);
    }
    return parsed as PerformanceReport;
  });

  const review = bundle.kind === 'suite'
    ? await reviewReportSuite(reports, provider)
    : await reviewSingle(reports[0]!, provider);

  const reviewErrors = validateAIReview(review);
  if (reviewErrors.length > 0) {
    throw new Error(`Invalid AI review: ${reviewErrors.join('; ')}`);
  }

  const paths = buildAIReviewArtifactPaths(item.path, bundle.kind);
  writeFileSync(paths.jsonPath, JSON.stringify(review, null, 2) + '\n');
  writeFileSync(paths.markdownPath, renderPerformanceReview({
    aiReview: review,
    title: `${bundle.kind === 'suite' ? 'AI Suite Review' : 'AI Review'} - ${item.runId}`,
  }));

  return { review, paths, bundle };
}
