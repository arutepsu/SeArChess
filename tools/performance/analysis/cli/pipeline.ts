import { readFileSync } from 'node:fs';
import { runPerformancePipeline } from '../application/runPerformancePipeline';

function readJsonFile(filePath: string): unknown {
  try {
    return JSON.parse(readFileSync(filePath, 'utf-8'));
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Error reading "${filePath}": ${msg}`);
  }
}

async function main(): Promise<void> {
  const baselinePath = process.argv[2] as string | undefined;
  const optimizedPath = process.argv[3] as string | undefined;
  const title = process.argv[4] as string | undefined;

  if (!baselinePath || !optimizedPath) {
    process.stderr.write('Usage: pipeline <baseline-input.json> <optimized-input.json> [title]\n');
    process.exit(1);
  }

  try {
    const baselineInput = readJsonFile(baselinePath);
    const optimizedInput = readJsonFile(optimizedPath);
    const markdown = await runPerformancePipeline({ baselineInput, optimizedInput, title });
    process.stdout.write(markdown);
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}

main();
