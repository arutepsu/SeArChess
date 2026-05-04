import chalk from 'chalk';

export function title(text: string): string {
  return chalk.bold.cyan(text);
}

export function section(text: string): string {
  return chalk.bold(text);
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

export function muted(text: string): string {
  return chalk.gray(text);
}
