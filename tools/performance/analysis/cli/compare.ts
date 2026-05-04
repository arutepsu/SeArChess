import { readFileSync } from 'node:fs';
import { compare } from '../index';
import type { PerformanceReport } from '../index';

function loadReport(filePath: string): PerformanceReport {
  try {
    return JSON.parse(readFileSync(filePath, 'utf-8')) as PerformanceReport;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`Error reading "${filePath}": ${msg}\n`);
    process.exit(1);
  }
}

function main(): void {
  const baselinePath  = process.argv[2] as string | undefined;
  const optimizedPath = process.argv[3] as string | undefined;
  if (!baselinePath || !optimizedPath) {
    process.stderr.write('Usage: compare <baseline-report.json> <optimized-report.json>\n');
    process.exit(1);
  }
  const baseline  = loadReport(baselinePath);
  const optimized = loadReport(optimizedPath);
  try {
    const result = compare(baseline, optimized);
    process.stdout.write(JSON.stringify(result, null, 2) + '\n');
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}
main();
