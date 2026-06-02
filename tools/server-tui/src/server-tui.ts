import { isCancel, select } from "@clack/prompts";
import pc from "picocolors";
import { openUrl } from "./browser";
import { commandRegistry, type CommandRegistryItem } from "./commands";
import { serverConfig } from "./config";
import { runCapturedCommand, runSpawnCommand } from "./runner";
import {
  showBlockingWarning,
  confirmBlockingCommand,
  pressEnterToReturn,
  showCancelled,
  showCommandPreview,
  showFailure,
  showGoodbye,
  showInfo,
  showPasswordlessSshInstructions,
  showStartup,
  showSuccess,
  showWarning
} from "./ui";

process.once("SIGINT", () => {
  showGoodbye();
  process.exit(0);
});

async function main(): Promise<void> {
  showStartup();

  while (true) {
    const selectedId = await select({
      message: "Choose a safe read-only action",
      options: commandRegistry.map((command) => ({
        value: command.id,
        label: command.title,
        hint: command.description
      }))
    });

    if (isCancel(selectedId)) {
      showGoodbye();
      return;
    }

    const selectedCommand = commandRegistry.find((command) => command.id === selectedId);
    if (!selectedCommand) {
      showFailure(1);
      continue;
    }

    if (selectedCommand.kind === "exit") {
      showGoodbye();
      return;
    }

    if (selectedCommand.kind === "browser") {
      await openBrowserUrls();
      continue;
    }

    if (selectedCommand.kind === "passwordless-ssh-check") {
      await checkPasswordlessSsh(selectedCommand);
      continue;
    }

    const spawnCommand = selectedCommand.execute();
    showCommandPreview(selectedCommand.title, selectedCommand.preview());

    if (selectedCommand.beforeRun) {
      showInfo(selectedCommand.title, selectedCommand.beforeRun);
    }

    if (selectedCommand.blocking) {
      showBlockingWarning();
      const shouldContinue = await confirmBlockingCommand(
        selectedCommand.confirmationMessage ?? "This command stays open until you exit it. Continue?"
      );
      if (!shouldContinue) {
        showCancelled();
        continue;
      }
    }

    const exitCode = await runSpawnCommand(spawnCommand);
    if (exitCode === 0) {
      showSuccess("Command finished successfully.");
    } else {
      showFailure(exitCode);
    }

    if (!selectedCommand.blocking) {
      await pressEnterToReturn();
    }
  }
}

async function checkPasswordlessSsh(
  selectedCommand: Extract<CommandRegistryItem, { kind: "passwordless-ssh-check" }>
): Promise<void> {
  const spawnCommand = selectedCommand.execute();
  showCommandPreview(selectedCommand.title, selectedCommand.preview());

  const result = await runCapturedCommand(spawnCommand);
  if (result.exitCode === 0 && result.stdout.includes("SSH_OK")) {
    showSuccess("Passwordless SSH is configured. The TUI should not ask for a password.");
  } else {
    showWarning("Passwordless SSH is not configured yet.");
    showPasswordlessSshInstructions();
  }

  await pressEnterToReturn();
}

async function openBrowserUrls(): Promise<void> {
  showInfo("Open local browser URLs", [
    "Make sure the SSH tunnel is already open.",
    `API local URL: ${serverConfig.apiLocalUrl}`,
    `Grafana local URL: ${serverConfig.grafanaLocalUrl}`
  ]);

  const apiExitCode = await openUrl(serverConfig.apiLocalUrl);
  const grafanaExitCode = await openUrl(serverConfig.grafanaLocalUrl);

  if (apiExitCode === 0 && grafanaExitCode === 0) {
    showSuccess("Opened local URLs in your default browser.");
    await pressEnterToReturn();
    return;
  }

  console.error(pc.red("Could not open one or more URLs automatically."));
  console.error(pc.yellow("Open them manually after the tunnel is running."));
  await pressEnterToReturn();
}

main().catch((error: unknown) => {
  console.error(pc.red("Unexpected TUI error."));
  console.error(error);
  process.exitCode = 1;
});
