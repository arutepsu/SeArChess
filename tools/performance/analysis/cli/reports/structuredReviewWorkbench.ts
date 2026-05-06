import { existsSync, readFileSync } from 'node:fs';
import { createAIReviewProvider } from '../../ai/aiReviewProviderFactory';
import type { ReviewInput, ReviewReader } from '../../ai/aiReviewModels';
import {
  generateStructuredReview,
  type GenerateStructuredReviewResult,
} from '../../application/reviewPerformance';
import { buildStructuredReviewArtifactPaths } from '../../application/artifacts/structuredReviewArtifacts';
import type { RunHistoryItem, RunHistoryKind } from '../../application/artifacts/runHistory';
import { renderStructuredReviewMarkdown } from '../../reporting/reviewReportMarkdownBuilder';
import type { PerformanceCliConfig } from '../config';
import { inputPrompt } from '../ui/prompts';
import * as theme from '../ui/theme';
import { renderMarkdownPreview, selectPreferredMarkdownReport } from '../ui/workbenchView';
import {
  readLastStructuredReviewOutputDir,
  rememberStructuredReviewOutputDir,
} from './structuredReviewOutputHistory';

export interface StructuredReviewWorkbenchDeps {
  input: typeof inputPrompt;
  createReader: () => ReviewReader;
  generate: typeof generateStructuredReview;
  renderMarkdown: typeof renderStructuredReviewMarkdown;
  readLastOutputDir: () => string | undefined;
  rememberOutputDir: (outputDir: string) => void;
  write: (text: string) => void;
}

export interface PreviewStructuredReviewWorkbenchDeps {
  input: typeof inputPrompt;
  exists: (path: string) => boolean;
  read: (path: string) => string;
  readLastOutputDir: () => string | undefined;
  renderPreview: typeof renderMarkdownPreview;
  write: (text: string) => void;
}

export interface InteractiveReviewInputRaw {
  moduleName?: string;
  userQuestion?: string;
  notes?: string;
  reviewText?: string;
}

function resolveOptionalAnswer(answer: string, defaultValue?: string): string | undefined {
  const trimmed = answer.trim();
  return trimmed.length > 0 ? trimmed : defaultValue;
}

export function parseStructuredReviewNotes(answer: string | undefined): string[] {
  return (answer ?? '')
    .split(',')
    .map((note) => note.trim())
    .filter((note) => note.length > 0);
}

export function buildInteractiveReviewInput(raw: InteractiveReviewInputRaw): ReviewInput {
  const moduleName = raw.moduleName?.trim() || 'performance-analysis';
  const userQuestion = raw.userQuestion?.trim() || 'Review the selected module.';
  const notes = parseStructuredReviewNotes(raw.notes);
  const reviewText = raw.reviewText?.trim();

  return {
    moduleName,
    userQuestion,
    notes,
    ...(reviewText ? { reviewText } : {}),
  };
}

function defaultStructuredReviewWorkbenchDeps(): StructuredReviewWorkbenchDeps {
  return {
    input: inputPrompt,
    createReader: createAIReviewProvider,
    generate: generateStructuredReview,
    renderMarkdown: renderStructuredReviewMarkdown,
    readLastOutputDir: readLastStructuredReviewOutputDir,
    rememberOutputDir: rememberStructuredReviewOutputDir,
    write: (text) => process.stdout.write(text),
  };
}

function defaultPreviewStructuredReviewWorkbenchDeps(): PreviewStructuredReviewWorkbenchDeps {
  return {
    input: inputPrompt,
    exists: existsSync,
    read: (path) => readFileSync(path, 'utf-8'),
    readLastOutputDir: readLastStructuredReviewOutputDir,
    renderPreview: renderMarkdownPreview,
    write: (text) => process.stdout.write(text),
  };
}

export async function runStructuredReviewWorkbenchFlow(
  config: PerformanceCliConfig,
  deps: StructuredReviewWorkbenchDeps = defaultStructuredReviewWorkbenchDeps(),
): Promise<number> {
  deps.write(`\n${theme.section('Structured Review')}\n`);

  const moduleName = await deps.input({
    message: 'Module name',
    default: 'performance-analysis',
  });

  const userQuestion = await deps.input({
    message: 'Review question',
    default: 'Review the selected module.',
  });

  const notes = await deps.input({
    message: 'Notes (comma separated, optional)',
  });

  const reviewText = await deps.input({
    message: 'Review text (optional)',
  });

  const defaultOutputDir = deps.readLastOutputDir() ?? config.outputRoot;
  const outputDir = resolveOptionalAnswer(await deps.input({
    message: 'Output directory (blank to preview only)',
    default: defaultOutputDir,
  }));

  const input = buildInteractiveReviewInput({
    moduleName,
    userQuestion,
    notes,
    reviewText,
  });

  const result: GenerateStructuredReviewResult = await deps.generate(
    input,
    deps.createReader(),
    outputDir ? { outputDir } : {},
  );

  deps.write(`${theme.success('Structured review generated.')}\n`);
  if (result.saved && result.paths) {
    if (outputDir) {
      deps.rememberOutputDir(outputDir);
    }
    deps.write(`${theme.muted(`JSON: ${result.paths.jsonPath}`)}\n`);
    deps.write(`${theme.muted(`Markdown: ${result.paths.markdownPath}`)}\n`);
  } else {
    deps.write(`\n${deps.renderMarkdown(result.report)}\n`);
  }

  return 0;
}

export async function runPreviewStructuredReviewWorkbenchFlow(
  config: PerformanceCliConfig,
  deps: PreviewStructuredReviewWorkbenchDeps = defaultPreviewStructuredReviewWorkbenchDeps(),
): Promise<number> {
  deps.write(`\n${theme.section('Preview Structured Review')}\n`);

  const defaultOutputDir = deps.readLastOutputDir() ?? config.outputRoot;
  const outputDir = resolveOptionalAnswer(await deps.input({
    message: 'Structured review output directory',
    default: defaultOutputDir,
  }), defaultOutputDir);

  if (!outputDir) {
    deps.write(`${theme.muted('No structured review artifact found. Generate a structured review first.')}\n`);
    return 0;
  }

  const paths = buildStructuredReviewArtifactPaths(outputDir);
  if (!deps.exists(paths.markdownPath)) {
    deps.write(`${theme.muted('No structured review artifact found. Generate a structured review first.')}\n`);
    return 0;
  }

  deps.write(`\n${deps.renderPreview(paths.markdownPath, deps.read(paths.markdownPath))}\n`);
  return 0;
}

// ---------------------------------------------------------------------------
// Generate structured review for a run from history
// ---------------------------------------------------------------------------

function moduleNameFromKind(kind: RunHistoryKind): string {
  switch (kind) {
    case 'k6-single':
    case 'k6-suite':
      return 'k6';
    case 'gatling-single':
      return 'gatling';
    case 'jmh-single':
      return 'jmh';
    default:
      return 'performance';
  }
}

export function buildReviewInputFromRun(item: RunHistoryItem, markdown: string): ReviewInput {
  return {
    moduleName: moduleNameFromKind(item.kind),
    userQuestion: 'Review this performance run.',
    notes: [`runId: ${item.runId}`, `phase: ${item.phase}`],
    reviewText: markdown,
  };
}

export interface GenerateStructuredReviewForRunDeps {
  selectMarkdown: (item: RunHistoryItem) => string | undefined;
  readFile: (path: string) => string;
  createReader: () => ReviewReader;
  generate: typeof generateStructuredReview;
  write: (text: string) => void;
}

function defaultGenerateStructuredReviewForRunDeps(): GenerateStructuredReviewForRunDeps {
  return {
    selectMarkdown: selectPreferredMarkdownReport,
    readFile: (path) => readFileSync(path, 'utf-8'),
    createReader: createAIReviewProvider,
    generate: generateStructuredReview,
    write: (text) => process.stdout.write(text),
  };
}

export async function generateStructuredReviewForRun(
  item: RunHistoryItem,
  deps: GenerateStructuredReviewForRunDeps = defaultGenerateStructuredReviewForRunDeps(),
): Promise<void> {
  const reportPath = deps.selectMarkdown(item);
  if (!reportPath) {
    deps.write(`${theme.muted('No Markdown report found for this run.')}\n`);
    return;
  }

  let markdown: string;
  try {
    markdown = deps.readFile(reportPath);
  } catch {
    deps.write(`${theme.muted(`Could not read report: ${reportPath}`)}\n`);
    return;
  }

  const input = buildReviewInputFromRun(item, markdown);
  const result = await deps.generate(input, deps.createReader(), { outputDir: item.path });

  deps.write(`${theme.success('Structured review generated.')}\n`);
  if (result.paths) {
    deps.write(`${theme.muted(`JSON: ${result.paths.jsonPath}`)}\n`);
    deps.write(`${theme.muted(`Markdown: ${result.paths.markdownPath}`)}\n`);
  }
}

export function selectStructuredReviewMarkdown(item: RunHistoryItem): string | undefined {
  const paths = buildStructuredReviewArtifactPaths(item.path);
  return existsSync(paths.markdownPath) ? paths.markdownPath : undefined;
}
