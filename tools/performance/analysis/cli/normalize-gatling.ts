import { readFileSync } from 'node:fs';
import { normalizeGatlingSummary } from '../normalization/gatlingSummaryNormalizer';
import type { NormalizerContext } from '../normalization/normalizerModels';

function readJsonFile(filePath: string): unknown {
  try {
    return JSON.parse(readFileSync(filePath, 'utf-8'));
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Error reading "${filePath}": ${msg}`);
  }
}

function main(): void {
  const summaryPath = process.argv[2] as string | undefined;
  const contextPath = process.argv[3] as string | undefined;
  if (!summaryPath || !contextPath) {
    process.stderr.write('Usage: normalize-gatling <summary.json> <context.json>\n');
    process.exit(1);
  }

  try {
    const raw = readJsonFile(summaryPath);
    const context = readJsonFile(contextPath) as NormalizerContext;
    const input = normalizeGatlingSummary(raw, context);
    process.stdout.write(JSON.stringify(input, null, 2) + '\n');
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}

main();
