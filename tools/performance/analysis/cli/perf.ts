import { TOP_LEVEL_HELP } from './help';
import { runInteractiveCli } from './interactive';
import { runK6Cli } from './run-k6';
import { runK6SuiteCli } from './run-k6-suite';

export interface PerfCliDeps {
  runInteractive: () => Promise<number>;
}

export function runPerfCli(args: string[], deps: PerfCliDeps = { runInteractive: runInteractiveCli }): number | Promise<number> {
  const command = args[0];

  if (!command || command === '--help' || command === '-h' || command === 'help') {
    process.stdout.write(TOP_LEVEL_HELP + '\n');
    return 0;
  }

  if (command === 'k6') {
    return runK6Cli(args.slice(1));
  }

  if (command === 'k6-suite') {
    return runK6SuiteCli(args.slice(1));
  }

  if (command === 'interactive' || command === 'start') {
    return deps.runInteractive();
  }

  process.stderr.write(`Unknown perf command: ${command}\n${TOP_LEVEL_HELP}\n`);
  return 1;
}

if (require.main === module) {
  void Promise.resolve(runPerfCli(process.argv.slice(2))).then((code) => {
    process.exitCode = code;
  });
}
