import { readFileSync } from 'node:fs';
import { reviewSingle, reviewComparison } from '../application/reviewPerformance';
import { createAIReviewProvider } from '../ai/aiReviewProviderFactory';
import {
  isValidPerformanceComparisonReport,
  validatePerformanceComparisonReport,
} from '../validation/validatePerformanceComparisonReport';
import {
  isValidPerformanceReport,
  validatePerformanceReport,
} from '../validation/validatePerformanceReport';

function isObject(data: unknown): data is Record<string, unknown> {
  return typeof data === 'object' && data !== null && !Array.isArray(data);
}

function hasFields(data: Record<string, unknown>, fields: readonly string[]): boolean {
  return fields.every((field) => data[field] !== undefined && data[field] !== null);
}

function hasComparisonShape(data: unknown): boolean {
  return isObject(data) && hasFields(data, ['verdict', 'improvement', 'baseline_summary', 'optimized_summary']);
}

function hasSingleRunShape(data: unknown): boolean {
  return isObject(data) && hasFields(data, ['bottleneck', 'summary', 'evidence', 'suggestions']);
}

function formatValidationErrors(kind: string, errors: string[]): string {
  return `Invalid ${kind}:\n${errors.map((e) => `- ${e}`).join('\n')}\n`;
}

async function main(): Promise<void> {
  const filePath = process.argv[2] as string | undefined;
  if (!filePath) {
    process.stderr.write('Usage: review <report.json>\n');
    process.exit(1);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(filePath, 'utf-8'));
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`Error reading "${filePath}": ${msg}\n`);
    process.exit(1);
  }
  try {
    const provider = createAIReviewProvider();
    const looksComparison = hasComparisonShape(parsed);
    const looksSingleRun = hasSingleRunShape(parsed);

    if (looksComparison && looksSingleRun) {
      process.stderr.write('Invalid review input: report shape is ambiguous between comparison and single-run.\n');
      process.exit(1);
    }

    if (looksComparison) {
      const errors = validatePerformanceComparisonReport(parsed);
      if (errors.length > 0) {
        process.stderr.write(formatValidationErrors('PerformanceComparisonReport', errors));
        process.exit(1);
      }
      if (!isValidPerformanceComparisonReport(parsed)) {
        process.stderr.write('Invalid PerformanceComparisonReport.\n');
        process.exit(1);
      }
      const aiReview = await reviewComparison(parsed, provider);
      process.stdout.write(JSON.stringify(aiReview, null, 2) + '\n');
      return;
    }

    if (looksSingleRun) {
      const errors = validatePerformanceReport(parsed);
      if (errors.length > 0) {
        process.stderr.write(formatValidationErrors('PerformanceReport', errors));
        process.exit(1);
      }
      if (!isValidPerformanceReport(parsed)) {
        process.stderr.write('Invalid PerformanceReport.\n');
        process.exit(1);
      }
      const aiReview = await reviewSingle(parsed, provider);
      process.stdout.write(JSON.stringify(aiReview, null, 2) + '\n');
      return;
    }

    process.stderr.write(
      'Invalid review input: expected a PerformanceReport or PerformanceComparisonReport shape.\n',
    );
    process.exit(1);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}
main();
