import { readFileSync } from 'node:fs';
import { analyze } from '../index';

function main(): void {
  const filePath = process.argv[2] as string | undefined;
  if (!filePath) {
    process.stderr.write('Usage: analyze <input.json>\n');
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
    const report = analyze(parsed);
    process.stdout.write(JSON.stringify(report, null, 2) + '\n');
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${msg}\n`);
    process.exit(1);
  }
}
main();
