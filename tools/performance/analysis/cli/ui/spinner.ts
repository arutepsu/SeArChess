import ora from 'ora';

export interface CliSpinner {
  update(text: string): void;
  succeed(text?: string): void;
  fail(text?: string): void;
  warn(text?: string): void;
  stop(): void;
}

export function startSpinner(text: string): CliSpinner {
  const spinner = ora(text).start();
  return {
    update: (message: string) => { spinner.text = message; },
    succeed: (message?: string) => { spinner.succeed(message); },
    fail: (message?: string) => { spinner.fail(message); },
    warn: (message?: string) => { spinner.warn(message); },
    stop: () => { spinner.stop(); },
  };
}
