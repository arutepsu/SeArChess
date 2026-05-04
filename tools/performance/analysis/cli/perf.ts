import { TOP_LEVEL_HELP } from './help';
import { runK6Cli } from './run-k6';

export function runPerfCli(args: string[]): number {
  const command = args[0];

  if (!command || command === '--help' || command === '-h' || command === 'help') {
    process.stdout.write(TOP_LEVEL_HELP + '\n');
    return 0;
  }

  if (command === 'k6') {
    return runK6Cli(args.slice(1));
  }

  process.stderr.write(`Unknown perf command: ${command}\n${TOP_LEVEL_HELP}\n`);
  return 1;
}

if (require.main === module) {
  process.exitCode = runPerfCli(process.argv.slice(2));
}
