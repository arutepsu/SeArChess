import { spawn } from "node:child_process";
import pc from "picocolors";

export type SpawnCommand = {
  command: string;
  args: string[];
};

export type CapturedCommandResult = {
  exitCode: number;
  stdout: string;
  stderr: string;
};

export async function runSpawnCommand(spawnCommand: SpawnCommand): Promise<number> {
  return new Promise((resolve) => {
    const child = spawn(spawnCommand.command, spawnCommand.args, {
      stdio: "inherit",
      windowsHide: false
    });

    child.once("error", (error: NodeJS.ErrnoException) => {
      if (error.code === "ENOENT" && spawnCommand.command === "ssh") {
        console.error(
          pc.red("ssh was not found. Install/enable OpenSSH Client on Windows or make sure ssh is on PATH.")
        );
      } else {
        console.error(pc.red(`Failed to start command: ${error.message}`));
      }
      resolve(1);
    });

    child.once("close", (code, signal) => {
      if (signal) {
        console.log(pc.yellow(`Command stopped by signal ${signal}.`));
        resolve(1);
        return;
      }

      resolve(code ?? 0);
    });
  });
}

export async function runCapturedCommand(spawnCommand: SpawnCommand): Promise<CapturedCommandResult> {
  return new Promise((resolve) => {
    const child = spawn(spawnCommand.command, spawnCommand.args, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true
    });

    let stdout = "";
    let stderr = "";

    child.stdout?.on("data", (chunk: Buffer) => {
      const text = chunk.toString();
      stdout += text;
      process.stdout.write(text);
    });

    child.stderr?.on("data", (chunk: Buffer) => {
      const text = chunk.toString();
      stderr += text;
      process.stderr.write(text);
    });

    child.once("error", (error: NodeJS.ErrnoException) => {
      if (error.code === "ENOENT" && spawnCommand.command === "ssh") {
        const message =
          "ssh was not found. Install/enable OpenSSH Client on Windows or make sure ssh is on PATH.";
        console.error(pc.red(message));
        stderr += message;
      } else {
        const message = `Failed to start command: ${error.message}`;
        console.error(pc.red(message));
        stderr += message;
      }

      resolve({ exitCode: 1, stdout, stderr });
    });

    child.once("close", (code, signal) => {
      if (signal) {
        const message = `Command stopped by signal ${signal}.`;
        console.log(pc.yellow(message));
        stderr += message;
        resolve({ exitCode: 1, stdout, stderr });
        return;
      }

      resolve({ exitCode: code ?? 0, stdout, stderr });
    });
  });
}
