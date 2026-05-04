import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { loadPerformanceConfig, performanceConfigRoot } from '../config';
import { resolveArtifactRoot, resolvePhaseRunsDir } from '../../application/artifacts/artifactRoot';

export interface EnvironmentCheckResult {
  nodeVersion: string;
  platform: string;
  cwd: string;
  configFound: boolean;
  configPath?: string;
  baseUrl?: string;
  outputRoot?: string;
  resolvedArtifactRoot: string;
  baselineRunsDirExists: boolean;
  optimizedRunsDirExists: boolean;
  k6Available: boolean;
  k6Version?: string;
  prometheusConfigExists?: boolean;
  grafanaDirExists?: boolean;
}

export function isCommandAvailable(command: string): boolean {
  const result = spawnSync(command, ['version'], {
    timeout: 3000,
    encoding: 'utf-8',
    stdio: 'pipe',
  });
  return result.error === undefined;
}

export function getCommandVersion(command: string, args: string[] = ['version']): string | undefined {
  const result = spawnSync(command, args, {
    timeout: 3000,
    encoding: 'utf-8',
    stdio: 'pipe',
  });
  if (result.error !== undefined || result.status === null) return undefined;
  const out = (result.stdout as string).trim();
  if (!out) return undefined;
  return out.split('\n')[0];
}

export function resolveObservabilityPaths(startDir = process.cwd()): {
  prometheusConfigPath: string;
  grafanaDirPath: string;
} {
  const repoRoot = performanceConfigRoot(startDir);
  const obsRoot = join(repoRoot, 'tools', 'performance', 'observability');
  return {
    prometheusConfigPath: join(obsRoot, 'prometheus', 'prometheus.yml'),
    grafanaDirPath: join(obsRoot, 'grafana'),
  };
}

export function runEnvironmentCheck(): EnvironmentCheckResult {
  const configRoot = performanceConfigRoot();
  const configFilePath = join(configRoot, 'performance.config.json');
  const configFound = existsSync(configFilePath);

  const config = loadPerformanceConfig();

  const artifactRoot = resolveArtifactRoot({ outputRoot: config.outputRoot });
  const baselineRunsDir = resolvePhaseRunsDir('baseline', { outputRoot: config.outputRoot });
  const optimizedRunsDir = resolvePhaseRunsDir('optimized', { outputRoot: config.outputRoot });

  const k6Version = getCommandVersion('k6', ['version']);
  const k6Available = k6Version !== undefined;

  const { prometheusConfigPath, grafanaDirPath } = resolveObservabilityPaths();

  return {
    nodeVersion: process.version,
    platform: process.platform,
    cwd: process.cwd(),
    configFound,
    configPath: configFound ? configFilePath : undefined,
    baseUrl: config.baseUrl,
    outputRoot: config.outputRoot,
    resolvedArtifactRoot: artifactRoot,
    baselineRunsDirExists: existsSync(baselineRunsDir),
    optimizedRunsDirExists: existsSync(optimizedRunsDir),
    k6Available,
    k6Version,
    prometheusConfigExists: existsSync(prometheusConfigPath),
    grafanaDirExists: existsSync(grafanaDirPath),
  };
}
