import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { resolveArtifactRoot } from '../../application/artifacts/artifactRoot';
import { loadPerformanceConfig, performanceConfigRoot } from '../config';

export interface WorkbenchSettingsView {
  configFile?: string;
  configFilePath?: string;
  suggestedConfigFilePath?: string;
  baseUrl?: string;
  outputRoot?: string;
  artifactRoot: string;
  defaultPhase?: 'baseline' | 'optimized';
  cpuUsagePercent?: number;
  memoryUsagePercent?: number;
  cwd: string;
}

export function buildWorkbenchSettingsView(startDir = process.cwd()): WorkbenchSettingsView {
  const configRoot = performanceConfigRoot(startDir);
  const configFile = join(configRoot, 'performance.config.json');
  const configFound = existsSync(configFile);
  const config = loadPerformanceConfig(startDir);

  return {
    configFile: configFound ? configFile : undefined,
    configFilePath: configFound ? configFile : undefined,
    suggestedConfigFilePath: configFile,
    baseUrl: config.baseUrl,
    outputRoot: config.outputRoot,
    artifactRoot: resolveArtifactRoot({ outputRoot: config.outputRoot, startDir }),
    defaultPhase: config.defaultPhase,
    cpuUsagePercent: config.cpuUsagePercent,
    memoryUsagePercent: config.memoryUsagePercent,
    cwd: process.cwd(),
  };
}
