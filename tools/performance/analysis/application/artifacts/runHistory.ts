import { existsSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { resolveArtifactRoot } from './artifactRoot';

export type RunHistoryPhase = 'baseline' | 'optimized' | 'unknown';
export type RunHistoryKind = 'k6-single' | 'k6-suite' | 'unknown';

export interface RunHistoryItem {
  runId: string;
  phase: RunHistoryPhase;
  path: string;
  createdAt?: Date;
  kind: RunHistoryKind;
  reports: string[];
  logs: string[];
}

function safeDirectoryEntries(path: string): string[] {
  if (!existsSync(path)) return [];
  try {
    return readdirSync(path);
  } catch {
    return [];
  }
}

function isDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function markdownReportsInRun(runPath: string): string[] {
  return safeDirectoryEntries(runPath)
    .filter((entry) => entry.endsWith('.md'))
    .map((entry) => join(runPath, entry))
    .sort();
}

function logsInRun(runPath: string): string[] {
  const logsDir = join(runPath, 'logs');
  return safeDirectoryEntries(logsDir)
    .filter((entry) => entry.endsWith('.log'))
    .map((entry) => join(logsDir, entry))
    .sort();
}

function determineKind(runPath: string, reports: string[]): RunHistoryKind {
  if (existsSync(join(runPath, 'k6_suite_report.md'))) return 'k6-suite';
  if (reports.some((r) => /k6_.*_report\.md$/.test(r.replace(/\\/g, '/')))) return 'k6-single';
  return 'unknown';
}

function discoverPhaseRuns(root: string, phase: 'baseline' | 'optimized'): RunHistoryItem[] {
  const runsDir = join(root, phase, 'runs');
  return safeDirectoryEntries(runsDir)
    .map((runId) => ({ runId, runPath: join(runsDir, runId) }))
    .filter(({ runPath }) => isDirectory(runPath))
    .map(({ runId, runPath }) => {
      const reports = markdownReportsInRun(runPath);
      const logs = logsInRun(runPath);
      const stats = statSync(runPath);
      return {
        runId,
        phase,
        path: runPath,
        createdAt: stats.mtime,
        kind: determineKind(runPath, reports),
        reports,
        logs,
      };
    });
}

export function findRunHistory(outputRoot?: string): RunHistoryItem[] {
  const root = resolveArtifactRoot({ outputRoot });
  return [
    ...discoverPhaseRuns(root, 'baseline'),
    ...discoverPhaseRuns(root, 'optimized'),
  ].sort((a, b) => {
    const timeDelta = (b.createdAt?.getTime() ?? 0) - (a.createdAt?.getTime() ?? 0);
    return timeDelta !== 0 ? timeDelta : b.runId.localeCompare(a.runId);
  });
}
