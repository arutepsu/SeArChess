import { readFileSync } from 'node:fs';
import { generateStructuredReview, reviewSingle, reviewComparison } from '../application/reviewPerformance';
import { createAIReviewProvider } from '../ai/aiReviewProviderFactory';
import {
  isValidPerformanceComparisonReport,
  validatePerformanceComparisonReport,
} from '../validation/validatePerformanceComparisonReport';
import {
  isValidPerformanceReport,
  validatePerformanceReport,
} from '../validation/validatePerformanceReport';
import {
  isValidReviewInput,
  validateReviewInput,
} from '../validation/validateReviewReport';
import { renderStructuredReviewMarkdown } from '../reporting/reviewReportMarkdownBuilder';

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

function hasReviewInputShape(data: unknown): boolean {
  return isObject(data) && (
    data['reviewText'] !== undefined ||
    data['moduleName'] !== undefined ||
    data['userQuestion'] !== undefined
  );
}

function formatValidationErrors(kind: string, errors: string[]): string {
  return `Invalid ${kind}:\n${errors.map((e) => `- ${e}`).join('\n')}\n`;
}

function parseArgs(args: string[]): { filePath?: string; outputDir?: string; error?: string } {
  const filePath = args[0];
  let outputDir: string | undefined;

  for (let i = 1; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === '--out' || arg === '-o') {
      const value = args[i + 1];
      if (!value) return { filePath, error: `${arg} requires a directory` };
      outputDir = value;
      i += 1;
    } else {
      return { filePath, error: `Unsupported argument: ${arg}` };
    }
  }

  return { filePath, outputDir };
}

async function main(): Promise<void> {
  const { filePath, outputDir, error } = parseArgs(process.argv.slice(2));
  if (error) {
    process.stderr.write(`${error}\nUsage: review <report.json> [--out <directory>]\n`);
    process.exit(1);
  }
  if (!filePath) {
    process.stderr.write('Usage: review <report.json> [--out <directory>]\n');
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
    const looksReviewInput = hasReviewInputShape(parsed);

    if ([looksComparison, looksSingleRun, looksReviewInput].filter(Boolean).length > 1) {
      process.stderr.write('Invalid review input: report shape is ambiguous.\n');
      process.exit(1);
    }

    if (looksReviewInput) {
      const errors = validateReviewInput(parsed);
      if (errors.length > 0) {
        process.stderr.write(formatValidationErrors('ReviewInput', errors));
        process.exit(1);
      }
      if (!isValidReviewInput(parsed)) {
        process.stderr.write('Invalid ReviewInput.\n');
        process.exit(1);
      }
      const result = await generateStructuredReview(parsed, provider, { outputDir });
      if (result.saved) {
        process.stdout.write(JSON.stringify({ saved: true, paths: result.paths }, null, 2) + '\n');
        return;
      }
      process.stdout.write(JSON.stringify({
        ...result.report,
        markdown: renderStructuredReviewMarkdown(result.report),
      }, null, 2) + '\n');
      return;
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
      'Invalid review input: expected a ReviewInput, PerformanceReport, or PerformanceComparisonReport shape.\n',
    );
    process.exit(1);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}
main();
