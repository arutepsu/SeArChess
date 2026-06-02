import { readFileSync } from 'node:fs';
import { runEnvironmentCheck } from './doctor/environmentCheck';
import { runK6ReportAsync, type K6Phase, type K6ReportProgressEvent, type K6TestName, type RunK6ReportOptions } from '../application/runK6Report';
import { runK6SuiteAsync, type K6SuiteProgressEvent, type RunK6SuiteOptions } from '../application/runK6Suite';
import { runGatlingReportAsync, type GatlingPattern, type GatlingReportProgressEvent, type GatlingTestName, type RunGatlingReportOptions } from '../application/runGatlingReport';
import { jmhProfileOptions, runJmhReportAsync, type JmhReportProgressEvent, type JmhWorkbenchProfile, type RunJmhReportOptions } from '../application/runJmhReport';
import { DEFAULT_GATLING_SCENARIO_PATTERN_ID, findGatlingScenarioPattern, listGatlingScenarioPatterns } from '../application/gatlingScenarioPatterns';
import { DEFAULT_JMH_GROUP_ID, findJmhBenchmarkGroup, listJmhBenchmarkGroups, resolveJmhPattern, type JmhBenchmarkGroupId } from '../application/jmhBenchmarkGroups';
import { createInteractiveRunOutDir } from './artifacts/interactiveRunArtifacts';
import { loadPerformanceConfig, resolvePerformanceOutputDir, type PerformanceCliConfig } from './config';
import {
  AI_REVIEW_DISABLED_MESSAGE,
  generateAIReviewForRun,
  selectAIReviewMarkdown,
} from './reports/aiReviewArtifacts';
import {
  generateStructuredReviewForRun,
  runPreviewStructuredReviewWorkbenchFlow,
  runStructuredReviewWorkbenchFlow,
  selectStructuredReviewMarkdown,
} from './reports/structuredReviewWorkbench';
import { findRunHistory, type RunHistoryItem } from './reports/runHistory';
import { buildWorkbenchSettingsView } from './settings/settingsView';
import { inputPrompt, selectPrompt, type SelectPromptChoice } from './ui/prompts';
import { startSpinner, type CliSpinner } from './ui/spinner';
import * as theme from './ui/theme';
import {
  artifactSummaryFromGatlingPaths,
  artifactSummaryFromJmhPaths,
  artifactSummaryFromK6Paths,
  artifactSummaryFromK6Suite,
  blankLineAfterRun,
  renderArtifactSummary,
  renderEnvironmentCheck,
  renderMarkdownPreview,
  renderObservabilityHint,
  renderProgressLine,
  renderJmhRunResult,
  renderJmhToolSummary,
  renderRunArtifactPaths,
  renderRunHistoryChoiceLabel,
  renderRunHistoryDetails,
  renderRunMetadata,
  renderSettingsView,
  renderSingleRunResult,
  renderStartupContext,
  renderSuiteRunResult,
  renderWorkbenchHeader,
  selectPreferredMarkdownReport,
} from './ui/workbenchView';
import { renderToolSummaryFromFile } from './ui/toolSummaryView';

type WorkbenchArea = 'k6' | 'gatling' | 'jmh' | 'reports' | 'settings' | 'doctor' | 'exit';
type K6WorkbenchAction = 'baseline' | 'load' | 'spike' | 'stress' | 'suite' | 'back';
type GatlingWorkbenchAction = GatlingTestName | 'back';
type JmhWorkbenchAction = JmhWorkbenchProfile | 'back';
type ReportsAction = 'browse-runs' | 'latest-suite-report' | 'structured-review' | 'preview-structured-review' | 'back';
export type RunDetailAction = 'preview' | 'paths' | 'generate-ai' | 'preview-ai' | 'generate-structured-review' | 'preview-structured-review' | 'back';

type InteractiveK6CommonOptions = Pick<RunK6SuiteOptions, 'baseUrl' | 'cpu' | 'memory' | 'phase' | 'out'>;
interface InteractiveK6RunOptions extends InteractiveK6CommonOptions {
  runId: string;
}

interface InteractiveJmhRunOptions {
  profile: JmhWorkbenchProfile;
  phase: K6Phase;
  out: string;
  runId: string;
  pattern: string;
  benchmarkGroupId: JmhBenchmarkGroupId;
  benchmarkGroupLabel: string;
}

export type ProgressSpinnerAction =
  | { kind: 'none' }
  | { kind: 'start'; text: string; test: string; logPath?: string }
  | { kind: 'succeed'; text: string }
  | { kind: 'warn'; text: string };

interface ActiveSpinnerState {
  spinner: CliSpinner;
  interval: NodeJS.Timeout;
}

export function resolveAnswer(answer: string, defaultValue?: string): string | undefined {
  const trimmed = answer.trim();
  return trimmed.length > 0 ? trimmed : defaultValue;
}

export function parsePercentAnswer(value: string | undefined, label: string): number {
  if (value === undefined || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100) {
    throw new Error(`${label} must be a number between 0 and 100`);
  }
  return parsed;
}

export function parsePhaseAnswer(value: string | undefined): K6Phase {
  if (value !== 'baseline' && value !== 'optimized') {
    throw new Error('Phase must be baseline or optimized');
  }
  return value;
}

export function reportActionChoices(): SelectPromptChoice<ReportsAction>[] {
  return [
    { name: 'Browse recent runs', value: 'browse-runs' },
    { name: 'Show latest suite report', value: 'latest-suite-report' },
    { name: 'Generate structured review', value: 'structured-review' },
    { name: 'Preview last structured review', value: 'preview-structured-review' },
    { name: 'Back', value: 'back' },
  ];
}

export function runDetailActionChoices(): SelectPromptChoice<RunDetailAction>[] {
  return [
    { name: 'Preview Markdown report', value: 'preview' },
    { name: 'Show artifact paths', value: 'paths' },
    { name: 'Generate AI review', value: 'generate-ai' },
    { name: 'Preview AI review', value: 'preview-ai' },
    { name: 'Generate structured review for this run', value: 'generate-structured-review' },
    { name: 'Preview structured review for this run', value: 'preview-structured-review' },
    { name: 'Back', value: 'back' },
  ];
}

export type RunDetailActionResult = 'back' | 'continue';

export interface RunDetailActionDeps {
  readFile: (path: string) => string;
  write: (text: string) => void;
  selectMarkdown: typeof selectPreferredMarkdownReport;
  selectAIMarkdown: typeof selectAIReviewMarkdown;
  selectStructuredMarkdown: typeof selectStructuredReviewMarkdown;
  generateAI: typeof generateAIReviewForRun;
  generateStructured: typeof generateStructuredReviewForRun;
  renderPreview: typeof renderMarkdownPreview;
  renderPaths: typeof renderRunArtifactPaths;
}

function defaultRunDetailActionDeps(): RunDetailActionDeps {
  return {
    readFile: (path) => readFileSync(path, 'utf-8'),
    write: (text) => process.stdout.write(text),
    selectMarkdown: selectPreferredMarkdownReport,
    selectAIMarkdown: selectAIReviewMarkdown,
    selectStructuredMarkdown: selectStructuredReviewMarkdown,
    generateAI: generateAIReviewForRun,
    generateStructured: generateStructuredReviewForRun,
    renderPreview: renderMarkdownPreview,
    renderPaths: renderRunArtifactPaths,
  };
}

export async function handleRunDetailAction(
  action: RunDetailAction,
  item: RunHistoryItem,
  config: PerformanceCliConfig,
  deps: RunDetailActionDeps,
): Promise<RunDetailActionResult> {
  switch (action) {
    case 'back':
      return 'back';

    case 'preview': {
      const reportPath = deps.selectMarkdown(item);
      if (!reportPath) {
        deps.write(`${theme.muted('No report found for this run.')}\n`);
        return 'continue';
      }
      let previewContent: string;
      try {
        previewContent = deps.readFile(reportPath);
      } catch {
        deps.write(`${theme.muted(`Could not read report: ${reportPath}`)}\n`);
        return 'continue';
      }
      deps.write(`\n${deps.renderPreview(reportPath, previewContent)}\n`);
      return 'continue';
    }

    case 'paths':
      deps.write(`\n${deps.renderPaths(item)}\n`);
      return 'continue';

    case 'generate-ai': {
      try {
        const result = await deps.generateAI(item, config);
        const label = result.bundle.kind === 'suite' ? 'AI suite review generated.' : 'AI review generated.';
        deps.write(`${theme.success(label)}\n`);
        deps.write(`${theme.muted(`JSON: ${result.paths.jsonPath}`)}\n`);
        deps.write(`${theme.muted(`Markdown: ${result.paths.markdownPath}`)}\n`);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        if (message === AI_REVIEW_DISABLED_MESSAGE) {
          deps.write(`${theme.muted(message)}\n`);
        } else {
          deps.write(`${theme.warning(message)}\n`);
        }
      }
      return 'continue';
    }

    case 'preview-ai': {
      const aiPath = deps.selectAIMarkdown(item);
      if (!aiPath) {
        deps.write(`${theme.muted('No AI review found for this run. Generate one first.')}\n`);
        return 'continue';
      }
      let aiContent: string;
      try {
        aiContent = deps.readFile(aiPath);
      } catch {
        deps.write(`${theme.muted(`Could not read AI review: ${aiPath}`)}\n`);
        return 'continue';
      }
      deps.write(`\n${deps.renderPreview(aiPath, aiContent)}\n`);
      return 'continue';
    }

    case 'generate-structured-review': {
      try {
        await deps.generateStructured(item);
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        deps.write(`${theme.warning(message)}\n`);
      }
      return 'continue';
    }

    case 'preview-structured-review': {
      const srPath = deps.selectStructuredMarkdown(item);
      if (!srPath) {
        deps.write(`${theme.muted('No structured review found for this run. Generate one first.')}\n`);
        return 'continue';
      }
      let srContent: string;
      try {
        srContent = deps.readFile(srPath);
      } catch {
        deps.write(`${theme.muted(`Could not read structured review: ${srPath}`)}\n`);
        return 'continue';
      }
      deps.write(`\n${deps.renderPreview(srPath, srContent)}\n`);
      return 'continue';
    }

    default: {
      const _unreachable: never = action;
      deps.write(`${theme.warning(`Unknown run action: ${String(_unreachable)}`)}\n`);
      return 'continue';
    }
  }
}

export function defaultOutputDirectory(config: PerformanceCliConfig, phase: K6Phase): string | undefined {
  return config.outputRoot ? resolvePerformanceOutputDir(config.outputRoot, phase) : undefined;
}

function runIdFromOutDir(out: string | undefined): string {
  return out ? out.split(/[\\/]/).filter(Boolean).at(-1) ?? '' : '';
}

export function buildInteractiveK6ReportOptions(test: K6TestName, commonOptions: InteractiveK6CommonOptions): RunK6ReportOptions {
  return {
    ...commonOptions,
    test,
    outputMode: 'log',
  };
}

export function buildInteractiveRunOutDir(
  phase: K6Phase,
  tool: string,
  testOrSuite: string,
  outputRoot?: string,
): { out: string; runId: string } {
  const out = createInteractiveRunOutDir(phase, tool, testOrSuite, outputRoot);
  return {
    out,
    runId: runIdFromOutDir(out),
  };
}

export function buildInteractiveK6SuiteOptions(commonOptions: InteractiveK6CommonOptions): RunK6SuiteOptions {
  return {
    ...commonOptions,
    outputMode: 'log',
  };
}

export function k6WorkbenchActionToTest(action: K6WorkbenchAction): K6TestName | undefined {
  return action === 'baseline' || action === 'load' || action === 'spike' || action === 'stress'
    ? action
    : undefined;
}

function check(text: string): string {
  return renderProgressLine('ok', text);
}

export function formatRunningSpinnerText(test: string, elapsedSeconds: number, logPath?: string): string {
  const base = `Running ${test}... ${elapsedSeconds}s elapsed.`;
  return logPath ? `${base} Raw output -> ${logPath}` : base;
}

export function k6ReportSpinnerAction(event: K6ReportProgressEvent): ProgressSpinnerAction {
  switch (event.step) {
    case 'k6:start':
      return {
        kind: 'start',
        text: `Running k6 ${event.test}... writing raw output to log`,
        test: `k6 ${event.test}`,
        logPath: event.path,
      };
    case 'k6:complete':
      return { kind: 'succeed', text: `k6 ${event.test} execution completed` };
    case 'k6:threshold-warning':
      return { kind: 'warn', text: `k6 ${event.test} completed with threshold warning; continuing` };
    default:
      return { kind: 'none' };
  }
}

export function k6SuiteSpinnerAction(event: K6SuiteProgressEvent): ProgressSpinnerAction {
  switch (event.step) {
    case 'suite:test-start':
      return event.test ? {
        kind: 'start',
        text: `Running ${event.test}...`,
        test: event.test,
      } : { kind: 'none' };
    case 'suite:test-progress':
      if (event.reportEvent?.step === 'k6:start') {
        return event.test ? {
          kind: 'start',
          text: `Running ${event.test}...`,
          test: event.test,
          logPath: event.reportEvent.path,
        } : { kind: 'none' };
      }
      if (event.reportEvent?.step === 'k6:threshold-warning') {
        return event.test
          ? { kind: 'warn', text: `${event.test} completed with threshold warning; continuing` }
          : { kind: 'warn', text: 'k6 test completed with threshold warning; continuing' };
      }
      return { kind: 'none' };
    case 'suite:test-complete':
      return event.test ? { kind: 'succeed', text: `${event.test} report generated` } : { kind: 'none' };
    default:
      return { kind: 'none' };
  }
}

export function formatK6ReportProgressEvent(event: K6ReportProgressEvent): string | undefined {
  switch (event.step) {
    case 'k6:start':
    case 'k6:complete':
    case 'k6:threshold-warning':
      return undefined;
    case 'summary:found':
      return check('summary exported');
    case 'context:written':
      return check('context written');
    case 'normalization:complete':
      return check('normalized input generated');
    case 'analysis:complete':
      return check('deterministic report generated');
    case 'markdown:written':
      return check('Markdown report generated');
  }
}

export function formatK6SuiteProgressEvent(event: K6SuiteProgressEvent): string | undefined {
  switch (event.step) {
    case 'suite:start':
      return undefined;
    case 'suite:test-start':
    case 'suite:test-progress':
    case 'suite:test-complete':
      return undefined;
    case 'suite:markdown-written':
      return check('suite report generated');
    case 'suite:complete':
      return undefined;
  }
}

export function buildInteractiveGatlingReportOptions(test: GatlingTestName, commonOptions: InteractiveK6CommonOptions): RunGatlingReportOptions {
  return {
    ...commonOptions,
    test,
    outputMode: 'log',
  };
}

export function buildInteractiveGatlingReportOptionsWithPattern(
  test: GatlingTestName,
  pattern: GatlingPattern,
  commonOptions: InteractiveK6CommonOptions,
): RunGatlingReportOptions {
  return {
    ...buildInteractiveGatlingReportOptions(test, commonOptions),
    gatlingPattern: pattern,
  };
}

export function gatlingReportSpinnerAction(event: GatlingReportProgressEvent): ProgressSpinnerAction {
  switch (event.step) {
    case 'gatling:start':
      return {
        kind: 'start',
        text: `Running Gatling ${event.test}... writing raw output to log`,
        test: `Gatling ${event.test}`,
        logPath: event.path,
      };
    case 'gatling:complete':
      return { kind: 'succeed', text: `Gatling ${event.test} execution completed` };
    default:
      return { kind: 'none' };
  }
}

export function formatGatlingReportProgressEvent(event: GatlingReportProgressEvent): string | undefined {
  switch (event.step) {
    case 'gatling:start':
    case 'gatling:complete':
      return undefined;
    case 'summary:found':
      return check('summary exported');
    case 'context:written':
      return check('context written');
    case 'normalization:complete':
      return check('normalized input generated');
    case 'analysis:complete':
      return check('deterministic report generated');
    case 'markdown:written':
      return check('Markdown report generated');
  }
}

export function buildInteractiveJmhReportOptions(input: InteractiveJmhRunOptions): RunJmhReportOptions {
  return {
    ...jmhProfileOptions(input.profile, input.pattern),
    profile: input.profile,
    phase: input.phase,
    out: input.out,
    runId: input.runId,
    outputMode: 'log',
    benchmarkGroupId: input.benchmarkGroupId,
    benchmarkGroupLabel: input.benchmarkGroupLabel,
  };
}

export function jmhReportSpinnerAction(event: JmhReportProgressEvent): ProgressSpinnerAction {
  switch (event.step) {
    case 'jmh:start':
      return {
        kind: 'start',
        text: `Running JMH ${event.profile}... writing raw output to log`,
        test: `JMH ${event.profile}`,
        logPath: event.path,
      };
    case 'jmh:complete':
      return { kind: 'succeed', text: 'JMH execution completed' };
    default:
      return { kind: 'none' };
  }
}

export function formatJmhReportProgressEvent(event: JmhReportProgressEvent): string | undefined {
  switch (event.step) {
    case 'jmh:start':
    case 'jmh:complete':
      return undefined;
    case 'raw-json:written':
      return check('raw JSON written');
    case 'structured-report:written':
      return check('structured report written');
    case 'markdown:written':
      return check('Markdown report written');
  }
}

function printProgress(line: string | undefined): void {
  if (line) {
    process.stdout.write(`${line}\n`);
  }
}

function applySpinnerAction(
  action: ProgressSpinnerAction,
  activeSpinner: ActiveSpinnerState | undefined,
): ActiveSpinnerState | undefined {
  switch (action.kind) {
    case 'start': {
      if (activeSpinner) {
        clearInterval(activeSpinner.interval);
        activeSpinner.spinner.stop();
      }
      const spinner = startSpinner(action.text);
      const startedAt = Date.now();
      const update = () => {
        const elapsedSeconds = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
        spinner.update(formatRunningSpinnerText(action.test, elapsedSeconds, action.logPath));
      };
      update();
      const interval = setInterval(update, 5000);
      return { spinner, interval };
    }
    case 'succeed':
      if (activeSpinner) {
        clearInterval(activeSpinner.interval);
        activeSpinner.spinner.succeed(action.text);
        return undefined;
      }
      process.stdout.write(`${theme.success(action.text)}\n`);
      return undefined;
    case 'warn':
      if (activeSpinner) {
        clearInterval(activeSpinner.interval);
        activeSpinner.spinner.warn(action.text);
        return undefined;
      }
      process.stderr.write(`${theme.warning(action.text)}\n`);
      return undefined;
    case 'none':
      return activeSpinner;
  }
}

function stopActiveSpinner(activeSpinner: ActiveSpinnerState | undefined): void {
  if (activeSpinner) {
    clearInterval(activeSpinner.interval);
    activeSpinner.spinner.stop();
  }
}

function failActiveSpinner(activeSpinner: ActiveSpinnerState | undefined, message: string): boolean {
  if (!activeSpinner) {
    return false;
  }
  clearInterval(activeSpinner.interval);
  activeSpinner.spinner.fail(message);
  return true;
}

async function promptCommonK6Options(config: PerformanceCliConfig, tool: string, testOrSuite: string): Promise<InteractiveK6RunOptions> {
  const defaultPhaseValue = config.defaultPhase ?? 'baseline';
  const baseUrl = resolveAnswer(await inputPrompt({
    message: 'Base URL',
    default: config.baseUrl,
  }), config.baseUrl);
  const phase = parsePhaseAnswer(resolveAnswer(await inputPrompt({
    message: 'Phase',
    default: defaultPhaseValue,
  }), defaultPhaseValue));
  const cpu = parsePercentAnswer(
    resolveAnswer(await inputPrompt({
      message: 'CPU usage %',
      default: config.cpuUsagePercent?.toString(),
    }), config.cpuUsagePercent?.toString()),
    'CPU usage %',
  );
  const memory = parsePercentAnswer(
    resolveAnswer(await inputPrompt({
      message: 'Memory usage %',
      default: config.memoryUsagePercent?.toString(),
    }), config.memoryUsagePercent?.toString()),
    'Memory usage %',
  );
  const defaultOut = defaultOutputDirectory(config, phase);
  const outputBase = resolveAnswer(await inputPrompt({
    message: 'Output directory',
    default: defaultOut,
  }), defaultOut);

  if (!baseUrl) {
    throw new Error('Base URL is required');
  }

  const runFolder = buildInteractiveRunOutDir(phase, tool, testOrSuite, outputBase);

  return {
    baseUrl,
    cpu,
    memory,
    phase,
    out: runFolder.out,
    runId: runFolder.runId,
  };
}

async function promptJmhOptions(config: PerformanceCliConfig, profile: JmhWorkbenchProfile): Promise<InteractiveJmhRunOptions> {
  const defaultPhaseValue = config.defaultPhase ?? 'baseline';
  const phase = parsePhaseAnswer(resolveAnswer(await inputPrompt({
    message: 'Phase',
    default: defaultPhaseValue,
  }), defaultPhaseValue));
  const defaultOut = defaultOutputDirectory(config, phase);
  const outputBase = resolveAnswer(await inputPrompt({
    message: 'Output directory',
    default: defaultOut,
  }), defaultOut);

  const groupId = await selectPrompt<JmhBenchmarkGroupId>({
    message: 'Benchmark group',
    choices: listJmhBenchmarkGroups().map((g) => ({
      name: `${g.label} — ${g.description}`,
      value: g.id,
    })),
    default: DEFAULT_JMH_GROUP_ID,
  });

  let pattern: string;
  if (groupId === 'custom') {
    pattern = resolveAnswer(await inputPrompt({
      message: 'JMH pattern',
      default: 'chess.benchmarks.*',
    }), 'chess.benchmarks.*') ?? 'chess.benchmarks.*';
  } else {
    pattern = resolveJmhPattern(groupId);
  }

  const group = findJmhBenchmarkGroup(groupId === 'custom' ? 'custom' : groupId);
  const runFolder = buildInteractiveRunOutDir(phase, 'jmh', profile, outputBase);

  return {
    profile,
    phase,
    out: runFolder.out,
    runId: runFolder.runId,
    pattern,
    benchmarkGroupId: groupId,
    benchmarkGroupLabel: group.label,
  };
}

async function browseRunDetailFlow(item: RunHistoryItem, config: PerformanceCliConfig): Promise<number> {
  const deps = defaultRunDetailActionDeps();
  while (true) {
    process.stdout.write(`\n${renderRunHistoryDetails(item)}\n`);
    const action = await selectPrompt<RunDetailAction>({
      message: 'Run Actions',
      choices: runDetailActionChoices(),
    });
    const result = await handleRunDetailAction(action, item, config, deps);
    if (result === 'back') return 0;
  }
}

async function browseRecentRunsFlow(config: PerformanceCliConfig): Promise<number> {
  const history = findRunHistory(config.outputRoot);
  if (history.length === 0) {
    process.stdout.write(`${theme.muted('No performance runs found yet. Run a k6 test first.')}\n`);
    return 0;
  }

  const selected = await selectPrompt<RunHistoryItem | 'back'>({
    message: 'Select a run',
    choices: [
      ...history.slice(0, 20).map((item) => ({
        name: renderRunHistoryChoiceLabel(item),
        value: item as RunHistoryItem | 'back',
      })),
      { name: 'Back', value: 'back' as const },
    ],
  });

  if (selected === 'back') return 0;
  return browseRunDetailFlow(selected, config);
}

export async function runReportsWorkbenchFlow(config: PerformanceCliConfig): Promise<number> {
  process.stdout.write(`\n${theme.section('Reports & History')}\n`);
  const action = await selectPrompt<ReportsAction>({
    message: 'Select report action',
    choices: reportActionChoices(),
  });

  if (action === 'back') return 0;

  if (action === 'browse-runs') {
    return browseRecentRunsFlow(config);
  }

  if (action === 'structured-review') {
    return runStructuredReviewWorkbenchFlow(config);
  }

  if (action === 'preview-structured-review') {
    return runPreviewStructuredReviewWorkbenchFlow(config);
  }

  const history = findRunHistory(config.outputRoot);
  const latestSuite = history.find((item) => item.kind === 'k6-suite');
  const suiteReport = latestSuite?.reports.find((report) => report.endsWith('k6_suite_report.md'));
  if (!suiteReport) {
    process.stdout.write(`${theme.muted('No k6 suite report found yet. Run full k6 suite first.')}\n`);
    return 0;
  }
  process.stdout.write(`${theme.section('Latest Suite Report')}\n${theme.muted(suiteReport)}\n`);
  return 0;
}

export async function runK6WorkbenchFlow(config: PerformanceCliConfig): Promise<number> {
  let activeSpinner: ActiveSpinnerState | undefined;

  try {
    process.stdout.write(`\n${theme.section('k6 Performance Tests')}\n`);
    const action = await selectPrompt<K6WorkbenchAction>({
      message: 'Select k6 action',
      choices: [
        { name: 'Run baseline test', value: 'baseline' },
        { name: 'Run load test', value: 'load' },
        { name: 'Run spike test', value: 'spike' },
        { name: 'Run stress test', value: 'stress' },
        { name: 'Run full k6 suite', value: 'suite' },
        { name: 'Back', value: 'back' },
      ],
    });

    if (action === 'back') {
      return 0;
    }

    if (action === 'suite') {
      const commonOptions = await promptCommonK6Options(config, 'k6', 'suite');
      process.stdout.write(`${renderRunMetadata({
        tool: 'k6',
        workload: 'suite',
        phase: commonOptions.phase,
        runId: commonOptions.runId,
      })}\n\n`);
      process.stdout.write(`${theme.section('Progress')}\n`);
      const result = await runK6SuiteAsync({
        ...buildInteractiveK6SuiteOptions(commonOptions),
        onProgress: (event) => {
          activeSpinner = applySpinnerAction(k6SuiteSpinnerAction(event), activeSpinner);
          printProgress(formatK6SuiteProgressEvent(event));
        },
      });
      stopActiveSpinner(activeSpinner);
      activeSpinner = undefined;
      if (result.results.some((r) => r.continuedAfterThresholdFailure)) {
        process.stderr.write(`${theme.warning('Warning: one or more k6 tests exited non-zero but summary exports existed.')}\n`);
      }
      process.stdout.write(`\n${renderSuiteRunResult(result)}\n\n`);
      process.stdout.write(`${renderArtifactSummary(artifactSummaryFromK6Suite(result, commonOptions.out ?? ''))}\n`);
      process.stdout.write(`\n${renderObservabilityHint(commonOptions.runId, commonOptions.baseUrl)}\n${blankLineAfterRun()}`);
      return 0;
    }

    const selected = k6WorkbenchActionToTest(action);
    if (!selected) {
      return 0;
    }
    const commonOptions = await promptCommonK6Options(config, 'k6', selected);
    process.stdout.write(`${renderRunMetadata({
      tool: 'k6',
      workload: selected,
      phase: commonOptions.phase,
      runId: commonOptions.runId,
    })}\n\n`);
    process.stdout.write(`${theme.section('Progress')}\n`);

    const options: RunK6ReportOptions = {
      ...buildInteractiveK6ReportOptions(selected, commonOptions),
      onProgress: (event) => {
        activeSpinner = applySpinnerAction(k6ReportSpinnerAction(event), activeSpinner);
        printProgress(formatK6ReportProgressEvent(event));
      },
    };

    const result = await runK6ReportAsync(options);
    stopActiveSpinner(activeSpinner);
    activeSpinner = undefined;
    if (result.continuedAfterThresholdFailure) {
      process.stderr.write(`${theme.warning(`Warning: k6 exited with code ${result.k6ExitCode}; continuing because summary export exists.`)}\n`);
    }
    process.stdout.write(`\n${renderSingleRunResult(result.report)}\n\n`);
    const toolSummary = renderToolSummaryFromFile('k6', result.artifactPaths.summaryPath);
    if (toolSummary) {
      process.stdout.write(`${toolSummary}\n\n`);
    }
    process.stdout.write(`${renderArtifactSummary(artifactSummaryFromK6Paths(result.artifactPaths))}\n`);
    process.stdout.write(`\n${renderObservabilityHint(commonOptions.runId, commonOptions.baseUrl)}\n${blankLineAfterRun()}`);
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (!failActiveSpinner(activeSpinner, msg)) {
      process.stderr.write(`${theme.error(msg)}\n`);
    }
    return 1;
  }
}

export async function runGatlingWorkbenchFlow(config: PerformanceCliConfig): Promise<number> {
  let activeSpinner: ActiveSpinnerState | undefined;

  try {
    process.stdout.write(`\n${theme.section('Gatling Simulations')}\n`);
    const action = await selectPrompt<GatlingWorkbenchAction>({
      message: 'Select Gatling action',
      choices: [
        { name: 'Run smoke Gatling simulation', value: 'smoke' },
        { name: 'Run load Gatling simulation', value: 'load' },
        { name: 'Run stress Gatling simulation', value: 'stress' },
        { name: 'Back', value: 'back' },
      ],
    });

    if (action === 'back') {
      return 0;
    }

    const commonOptions = await promptCommonK6Options(config, 'gatling', action);
    const gatlingPattern = await selectPrompt<GatlingPattern>({
      message: 'Gatling pattern',
      choices: listGatlingScenarioPatterns().map((pattern) => ({
        name: `${pattern.label} - ${pattern.description}`,
        value: pattern.id,
      })),
      default: DEFAULT_GATLING_SCENARIO_PATTERN_ID,
    });
    const gatlingPatternLabel = findGatlingScenarioPattern(gatlingPattern).label;
    process.stdout.write(`${renderRunMetadata({
      tool: 'gatling',
      workload: action,
      pattern: gatlingPatternLabel,
      phase: commonOptions.phase,
      runId: commonOptions.runId,
    })}\n\n`);
    process.stdout.write(`${theme.section('Progress')}\n`);

    const options: RunGatlingReportOptions = {
      ...buildInteractiveGatlingReportOptionsWithPattern(action, gatlingPattern, commonOptions),
      onProgress: (event) => {
        activeSpinner = applySpinnerAction(gatlingReportSpinnerAction(event), activeSpinner);
        printProgress(formatGatlingReportProgressEvent(event));
      },
    };

    const result = await runGatlingReportAsync(options);
    stopActiveSpinner(activeSpinner);
    activeSpinner = undefined;
    process.stdout.write(`\n${renderSingleRunResult(result.report)}\n\n`);
    const toolSummary = renderToolSummaryFromFile('gatling', result.artifactPaths.summaryPath, result.artifactPaths.htmlReportPath);
    if (toolSummary) {
      process.stdout.write(`${toolSummary}\n\n`);
    }
    process.stdout.write(`${renderArtifactSummary(artifactSummaryFromGatlingPaths(result.artifactPaths))}\n`);
    process.stdout.write(`\n${renderObservabilityHint(commonOptions.runId, commonOptions.baseUrl)}\n${blankLineAfterRun()}`);
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (!failActiveSpinner(activeSpinner, msg)) {
      process.stderr.write(`${theme.error(msg)}\n`);
    }
    return 1;
  }
}

export async function runJmhWorkbenchFlow(config: PerformanceCliConfig): Promise<number> {
  let activeSpinner: ActiveSpinnerState | undefined;

  try {
    process.stdout.write(`\n${theme.section('JMH Benchmarks')}\n`);
    const action = await selectPrompt<JmhWorkbenchAction>({
      message: 'Select JMH action',
      choices: [
        { name: 'Run smoke JMH benchmark', value: 'smoke' },
        { name: 'Run baseline JMH benchmark', value: 'baseline' },
        { name: 'Run GC/allocation JMH benchmark', value: 'gc' },
        { name: 'Back', value: 'back' },
      ],
    });

    if (action === 'back') {
      return 0;
    }

    const commonOptions = await promptJmhOptions(config, action);
    process.stdout.write(`${renderRunMetadata({
      tool: 'jmh',
      workload: action,
      group: commonOptions.benchmarkGroupLabel,
      phase: commonOptions.phase,
      runId: commonOptions.runId,
    })}\n\n`);
    process.stdout.write(`${theme.section('Progress')}\n`);

    const options: RunJmhReportOptions = {
      ...buildInteractiveJmhReportOptions(commonOptions),
      onProgress: (event) => {
        activeSpinner = applySpinnerAction(jmhReportSpinnerAction(event), activeSpinner);
        printProgress(formatJmhReportProgressEvent(event));
      },
    };

    const result = await runJmhReportAsync(options);
    stopActiveSpinner(activeSpinner);
    activeSpinner = undefined;
    process.stdout.write(`\n${renderJmhRunResult(result.report)}\n\n`);
    process.stdout.write(`${renderJmhToolSummary(result.report)}\n\n`);
    process.stdout.write(`${renderArtifactSummary(artifactSummaryFromJmhPaths(result.artifactPaths))}\n${blankLineAfterRun()}`);
    return 0;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (!failActiveSpinner(activeSpinner, msg)) {
      process.stderr.write(`${theme.error(msg)}\n`);
    }
    return 1;
  }
}

export async function runInteractiveCli(): Promise<number> {
  try {
    process.stdout.write(`${theme.title(renderWorkbenchHeader())}\n\n`);

    try {
      const ctx = runEnvironmentCheck();
      process.stdout.write(`${renderStartupContext(ctx)}\n\n`);
    } catch {
      process.stdout.write(`${theme.muted('Startup context unavailable. Use Environment Check for details.')}\n\n`);
    }

    while (true) {
      const area = await selectPrompt<WorkbenchArea>({
        message: 'Select area',
        choices: [
          { name: 'k6 Performance Tests', value: 'k6' },
          { name: 'Reports & History', value: 'reports' },
          { name: 'Settings', value: 'settings' },
          { name: 'Environment Check', value: 'doctor' },
          { name: 'Gatling Simulations', value: 'gatling' },
          { name: 'JMH Benchmarks', value: 'jmh' },
          { name: 'Exit', value: 'exit' },
        ],
      });

      if (area === 'exit') {
        return 0;
      }

      if (area === 'k6') {
        const config = loadPerformanceConfig();
        const result = await runK6WorkbenchFlow(config);
        if (result !== 0) return result;
        continue;
      }
      if (area === 'reports') {
        const config = loadPerformanceConfig();
        const result = await runReportsWorkbenchFlow(config);
        if (result !== 0) return result;
        continue;
      }
      if (area === 'doctor') {
        const result = runEnvironmentCheck();
        process.stdout.write(`\n${renderEnvironmentCheck(result)}\n`);
        continue;
      }
      if (area === 'gatling') {
        const config = loadPerformanceConfig();
        const result = await runGatlingWorkbenchFlow(config);
        if (result !== 0) return result;
        continue;
      }
      if (area === 'jmh') {
        const config = loadPerformanceConfig();
        const result = await runJmhWorkbenchFlow(config);
        if (result !== 0) return result;
        continue;
      }
      if (area === 'settings') {
        process.stdout.write(`\n${renderSettingsView(buildWorkbenchSettingsView())}\n`);
        continue;
      }
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    process.stderr.write(`${theme.error(msg)}\n`);
    return 1;
  }
}
