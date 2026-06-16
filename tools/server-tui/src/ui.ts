import { createInterface } from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import { confirm, intro, isCancel, log, note, outro } from "@clack/prompts";
import pc from "picocolors";
import { SSH_TARGET, serverConfig } from "./config";

export function showStartup(): void {
  const banner = [
    pc.cyan(pc.bold("================================")),
    pc.cyan(pc.bold(`  ${serverConfig.appName}`)),
    pc.cyan(pc.bold("================================"))
  ].join("\n");

  intro(banner);
  log.warn(pc.yellow(pc.bold("Turn on VPN first.")));
  note(
    [
      `${pc.bold("Warning:")}`,
      "Turn on VPN first.",
      "",
      `${pc.bold("Target:")}`,
      SSH_TARGET,
      "",
      `${pc.bold("Authentication:")}`,
      "Uses normal SSH authentication.",
      "No passwords are stored.",
      "For passwordless login, configure an SSH key.",
      "",
      `${pc.bold("Remote repo:")} ${serverConfig.remoteRepoPath}`,
      `${pc.bold("Kubernetes namespace:")} ${serverConfig.kubernetesNamespace}`,
      `${pc.bold("Argo CD namespace:")} ${serverConfig.argoCdNamespace}`
    ].join("\n"),
    "Startup"
  );
}

export function showCommandPreview(title: string, preview: string): void {
  note(pc.gray(preview), pc.cyan(pc.bold(title)));
}

export function showInfo(title: string, lines: string[]): void {
  note(lines.join("\n"), pc.cyan(title));
}

export function showBlockingWarning(): void {
  log.warn(pc.yellow("This is a blocking command. You will return to the menu after it exits."));
}

export async function confirmBlockingCommand(message: string): Promise<boolean> {
  const result = await confirm({
    message,
    initialValue: false
  });

  if (isCancel(result)) {
    return false;
  }

  return result;
}

export function showSuccess(message: string): void {
  log.success(pc.green(message));
}

export function showWarning(message: string): void {
  log.warn(pc.yellow(message));
}

export function showPasswordlessSshInstructions(): void {
  note(
    [
      "Step 1: Create an SSH key if you do not already have one:",
      "",
      pc.gray('ssh-keygen -t ed25519 -C "searchess-server"'),
      "",
      "Accept the default path unless you know what you are doing.",
      "",
      "Step 2: Copy the public key to the university server.",
      "This will ask for the SSH password one last time:",
      "",
      pc.gray(
        'type $env:USERPROFILE\\.ssh\\id_ed25519.pub | ssh chess@141.37.123.125 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"'
      ),
      "",
      "Step 3: Test it:",
      "",
      pc.gray("ssh chess@141.37.123.125"),
      "",
      "After this, the normal TUI actions should connect without asking for the server password."
    ].join("\n"),
    pc.yellow("Passwordless SSH setup")
  );
}

export function showFailure(exitCode: number): void {
  log.error(pc.red(`Command finished with exit code ${exitCode}.`));
  log.message(pc.yellow("Check the output above. VPN, SSH auth, or server readiness may need attention."));
}

export async function pressEnterToReturn(): Promise<void> {
  const rl = createInterface({ input, output });

  try {
    await rl.question(pc.gray("Press Enter to return to menu"));
  } finally {
    rl.close();
  }
}

export function showCancelled(): void {
  log.warn(pc.yellow("Cancelled."));
}

export function showGoodbye(): void {
  outro(pc.green("Done. No secrets stored, no cluster mutations run."));
}
