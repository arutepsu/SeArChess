import chalk from 'chalk';

export type SemanticStyle = 'success' | 'warning' | 'error' | 'muted' | 'neutral';

const SECTION_RULE = '-'.repeat(40);

export function title(text: string): string {
  return chalk.bold.cyan(text);
}

export function section(text: string): string {
  return chalk.bold.cyan(text);
}

export function sectionHeader(text: string): string {
  return [muted(SECTION_RULE), section(text), muted(SECTION_RULE)].join('\n');
}

export function success(text: string): string {
  return chalk.green(text);
}

export function warning(text: string): string {
  return chalk.yellow(text);
}

export function error(text: string): string {
  return chalk.red(text);
}

export function info(text: string): string {
  return chalk.cyan(text);
}

export function muted(text: string): string {
  return chalk.gray(text);
}

export function label(text: string): string {
  return chalk.bold(text);
}

export function shortenPathForDisplay(filePath: string, maxLength = Math.max(48, Math.min(process.stdout.columns || 96, 120))): string {
  if (!filePath || filePath.length <= maxLength) {
    return filePath;
  }

  const normalized = filePath.replace(/\//g, '\\');
  const separatorIndex = normalized.lastIndexOf('\\');
  const separator = filePath.includes('/') && !filePath.includes('\\') ? '/' : '\\';
  const fileName = separatorIndex >= 0 ? filePath.slice(separatorIndex + 1) : filePath.slice(-Math.min(filePath.length, 24));
  const suffix = fileName.length + 5 >= maxLength
    ? fileName.slice(-(maxLength - 5))
    : fileName;
  const marker = `...${separator}`;
  const prefixLength = Math.max(1, maxLength - suffix.length - marker.length);
  return `${filePath.slice(0, prefixLength)}${marker}${suffix}`;
}

export function path(text: string, maxLength?: number): string {
  return chalk.blue.dim(shortenPathForDisplay(text, maxLength));
}

export function applySemanticStyle(value: string, style: SemanticStyle): string {
  switch (style) {
    case 'success':
      return success(value);
    case 'warning':
      return warning(value);
    case 'error':
      return error(value);
    case 'muted':
      return muted(value);
    case 'neutral':
      return value;
  }
}

export function semanticStyle(value: string): SemanticStyle {
  const normalized = value.trim().toLowerCase();
  if (['healthy', 'ok', 'pass', 'passed', 'success'].includes(normalized)) {
    return 'success';
  }
  if (['warning', 'warn', 'medium'].includes(normalized)) {
    return 'warning';
  }
  if (['failed', 'fail', 'ko', 'critical', 'error'].includes(normalized)) {
    return 'error';
  }
  if (['unknown', 'low', 'n/a'].includes(normalized)) {
    return 'muted';
  }
  return 'neutral';
}

export function semanticValue(value: string): string {
  return applySemanticStyle(value, semanticStyle(value));
}
