import { readFileSync } from 'node:fs';
import { renderPerformanceReview } from '../application/renderPerformanceReview';
import type { MarkdownReportInput } from '../reporting/markdownReportBuilder';

function isObject(input: unknown): input is Record<string, unknown> {
  return input !== null && typeof input === 'object' && !Array.isArray(input);
}

function hasRenderableInput(input: Record<string, unknown>): boolean {
  return (
    input['performanceReport'] !== undefined ||
    input['comparisonReport'] !== undefined ||
    input['aiReview'] !== undefined
  );
}

function main(): void {
  const filePath = process.argv[2] as string | undefined;
  if (!filePath) {
    process.stderr.write('Usage: render <input.json>\n');
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
    if (!isObject(parsed)) {
      process.stderr.write('Invalid render input: input must be an object.\n');
      process.exit(1);
    }

    if (!hasRenderableInput(parsed)) {
      process.stderr.write(
        'Invalid render input: expected performanceReport, comparisonReport, or aiReview.\n',
      );
      process.exit(1);
    }

    process.stdout.write(renderPerformanceReview(parsed as MarkdownReportInput));
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}

main();
