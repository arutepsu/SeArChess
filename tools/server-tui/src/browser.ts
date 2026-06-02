import { spawn } from "node:child_process";

export function openUrl(url: string): Promise<number> {
  const command =
    process.platform === "win32" ? "cmd" : process.platform === "darwin" ? "open" : "xdg-open";
  const args = process.platform === "win32" ? ["/c", "start", "", url] : [url];

  return new Promise((resolve) => {
    const child = spawn(command, args, {
      detached: true,
      stdio: "ignore",
      windowsHide: true
    });

    child.once("error", () => resolve(1));
    child.once("spawn", () => {
      child.unref();
      resolve(0);
    });
  });
}
