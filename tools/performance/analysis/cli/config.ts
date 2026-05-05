import { existsSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';

export interface PerformanceCliConfig {
  baseUrl?: string;
  outputRoot?: string;
  defaultPhase?: 'baseline' | 'optimized';
  cpuUsagePercent?: number;
  memoryUsagePercent?: number;
  ai?: {
    enabled?: boolean;
    provider?: 'stub';
    autoReview?: boolean;
  };
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isPercent(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
}

function knownRepoRoot(): string {
  return resolve(__dirname, '../../../../..');
}

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

export function performanceConfigRoot(startDir = process.cwd()): string {
  const configPath = findConfigPath(startDir);
  return configPath ? dirname(configPath) : knownRepoRoot();
}

export function resolvePerformanceOutputDir(outputRoot: string, phase: 'baseline' | 'optimized', startDir = process.cwd()): string {
  const root = isAbsolute(outputRoot) ? outputRoot : join(performanceConfigRoot(startDir), outputRoot);
  return join(root, phase);
}

export function loadPerformanceConfig(startDir = process.cwd()): PerformanceCliConfig {
  const configPath = findConfigPath(startDir);
  if (!configPath) return {};

  let parsed: unknown;
  try {
    parsed = JSON.parse(readFileSync(configPath, 'utf-8'));
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    throw new Error(`Invalid performance.config.json: ${msg}`);
  }

  if (!isObject(parsed)) {
    throw new Error('Invalid performance.config.json: root must be an object');
  }

  if (parsed['defaultPhase'] !== undefined && parsed['defaultPhase'] !== 'baseline' && parsed['defaultPhase'] !== 'optimized') {
    throw new Error('Invalid performance.config.json: defaultPhase must be baseline or optimized');
  }
  if (parsed['cpuUsagePercent'] !== undefined && !isPercent(parsed['cpuUsagePercent'])) {
    throw new Error('Invalid performance.config.json: cpuUsagePercent must be a number between 0 and 100');
  }
  if (parsed['memoryUsagePercent'] !== undefined && !isPercent(parsed['memoryUsagePercent'])) {
    throw new Error('Invalid performance.config.json: memoryUsagePercent must be a number between 0 and 100');
  }
  if (parsed['baseUrl'] !== undefined && typeof parsed['baseUrl'] !== 'string') {
    throw new Error('Invalid performance.config.json: baseUrl must be a string');
  }
  if (parsed['outputRoot'] !== undefined && typeof parsed['outputRoot'] !== 'string') {
    throw new Error('Invalid performance.config.json: outputRoot must be a string');
  }
  if (parsed['ai'] !== undefined) {
    if (!isObject(parsed['ai'])) {
      throw new Error('Invalid performance.config.json: ai must be an object');
    }
    const ai = parsed['ai'];
    if (ai['enabled'] !== undefined && typeof ai['enabled'] !== 'boolean') {
      throw new Error('Invalid performance.config.json: ai.enabled must be a boolean');
    }
    if (ai['provider'] !== undefined && ai['provider'] !== 'stub') {
      throw new Error('Invalid performance.config.json: ai.provider must be stub');
    }
    if (ai['autoReview'] !== undefined && typeof ai['autoReview'] !== 'boolean') {
      throw new Error('Invalid performance.config.json: ai.autoReview must be a boolean');
    }
  }

  return parsed as PerformanceCliConfig;
}
