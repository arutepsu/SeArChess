export const TOP_LEVEL_HELP = [
  'Usage: perf <command> [options]',
  '',
  'Defaults may be supplied by repo-root performance.config.json.',
  'Explicit CLI arguments override config values.',
  '',
  'Commands:',
  '  k6      Run a k6 test, normalize, analyze, and write artifacts',
  '  help    Show this help',
  '',
  'Examples:',
  '  perf k6 --test load',
  '  perf k6 --test load --base-url http://localhost:10000/api --cpu 72 --memory 61 --phase baseline',
].join('\n');

export const K6_HELP = [
  'Usage: perf k6 --test <baseline|load|stress|spike> --base-url <url> --cpu <number> --memory <number> --phase <baseline|optimized> [--out <directory>]',
  '',
  'The base URL, output root, default phase, CPU, and memory may come from performance.config.json.',
  'Explicit CLI arguments override config values.',
  '',
  'Examples:',
  '  perf k6 --test load',
  '  perf k6 --test stress --base-url http://localhost:10000/api --cpu 72 --memory 61 --phase baseline',
  '  perf k6 --test load --base-url http://localhost:10000/api --cpu 65 --memory 58 --phase optimized --out docs/performance/optimized',
].join('\n');
