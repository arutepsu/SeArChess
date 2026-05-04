import { existsSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';

function findConfigPath(startDir: string): string | undefined {
  let current = resolve(startDir);
  while (true) {
    const candidate = join(current, 'performance.config.json');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(current);
    if (parent === current) return undefined;
    current = parent;
  }
}

export function resolveRepoRoot(): string {
  // dist/application/artifacts/ is 6 hops from repo root
  return resolve(__dirname, '../../../../../..');
}

export function resolveArtifactRoot(options?: { outputRoot?: string; startDir?: string }): string {
  if (options?.outputRoot) {
    if (isAbsolute(options.outputRoot)) return options.outputRoot;
    const configPath = findConfigPath(options.startDir ?? process.cwd());
    const configRoot = configPath ? dirname(configPath) : resolveRepoRoot();
    return join(configRoot, options.outputRoot);
  }
  return join(resolveRepoRoot(), 'docs', 'performance');
}

export function resolvePhaseRoot(
  phase: 'baseline' | 'optimized',
  options?: { outputRoot?: string; startDir?: string },
): string {
  return join(resolveArtifactRoot(options), phase);
}

export function resolvePhaseRunsDir(
  phase: 'baseline' | 'optimized',
  options?: { outputRoot?: string; startDir?: string },
): string {
  return join(resolvePhaseRoot(phase, options), 'runs');
}
