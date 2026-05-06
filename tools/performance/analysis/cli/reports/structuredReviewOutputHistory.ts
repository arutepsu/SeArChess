import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { performanceConfigRoot } from '../config';

interface StructuredReviewOutputHistory {
  lastStructuredReviewOutputDir?: string;
}

export function structuredReviewOutputHistoryPath(startDir = process.cwd()): string {
  return join(performanceConfigRoot(startDir), '.structured-review-workbench.json');
}

function parseHistory(content: string): StructuredReviewOutputHistory {
  const parsed = JSON.parse(content) as unknown;
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {};
  }

  const value = (parsed as Record<string, unknown>)['lastStructuredReviewOutputDir'];
  return typeof value === 'string' && value.trim().length > 0
    ? { lastStructuredReviewOutputDir: value }
    : {};
}

export function readLastStructuredReviewOutputDir(startDir = process.cwd()): string | undefined {
  const path = structuredReviewOutputHistoryPath(startDir);
  if (!existsSync(path)) return undefined;

  try {
    return parseHistory(readFileSync(path, 'utf-8')).lastStructuredReviewOutputDir;
  } catch {
    return undefined;
  }
}

export function rememberStructuredReviewOutputDir(outputDir: string, startDir = process.cwd()): void {
  const trimmed = outputDir.trim();
  if (trimmed.length === 0) return;

  const path = structuredReviewOutputHistoryPath(startDir);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify({ lastStructuredReviewOutputDir: trimmed }, null, 2) + '\n', 'utf-8');
}
