import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReviewReport } from '../../ai/aiReviewModels';
import { renderStructuredReviewMarkdown } from '../../reporting/reviewReportMarkdownBuilder';

export interface StructuredReviewArtifactPaths {
  jsonPath: string;
  markdownPath: string;
}

export function buildStructuredReviewArtifactPaths(outputDir: string): StructuredReviewArtifactPaths {
  return {
    jsonPath: join(outputDir, 'review_report.json'),
    markdownPath: join(outputDir, 'review_report.md'),
  };
}

export function saveStructuredReviewArtifacts(
  report: ReviewReport,
  outputDir: string,
): StructuredReviewArtifactPaths {
  mkdirSync(outputDir, { recursive: true });
  const paths = buildStructuredReviewArtifactPaths(outputDir);
  writeFileSync(paths.jsonPath, JSON.stringify(report, null, 2) + '\n', 'utf-8');
  writeFileSync(paths.markdownPath, renderStructuredReviewMarkdown(report), 'utf-8');
  return paths;
}
