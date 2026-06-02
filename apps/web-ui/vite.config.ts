import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { spawn, exec } from "child_process";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let botProcess: any = null;
let botStatus = "stopped"; // "stopped", "starting", "running", "stopping"
let botLogs: string[] = [];

function appendLog(line: string) {
  botLogs.push(line);
  if (botLogs.length > 500) {
    botLogs.shift();
  }
  console.log(`[Bot Runner] ${line}`);
}

const devBotPlugin = () => ({
  name: "dev-bot-runner",
  configureServer(server: any) {
    server.middlewares.use((req: any, res: any, next: any) => {
      if (req.url === "/api/dev-bot/start" && req.method === "POST") {
        if (botStatus !== "stopped") {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: `Bot is already in status: ${botStatus}` }));
          return;
        }

        botStatus = "starting";
        botLogs = [];
        appendLog("Spawning sbt project lichessBot run...");

        const rootDir = path.resolve(__dirname, "../../");

        botProcess = spawn("sbt", ['"project lichessBot" run'], {
          cwd: rootDir,
          shell: true,
          env: {
            ...process.env
          }
        });

        botProcess.stdout.on("data", (data: any) => {
          const text = data.toString();
          text.split("\n").forEach((line: string) => {
            const trimmed = line.trim();
            if (trimmed) {
              appendLog(trimmed);
              if (trimmed.includes("Starting event stream listening") || trimmed.includes("Successfully logged in as bot ID")) {
                botStatus = "running";
              }
            }
          });
        });

        botProcess.stderr.on("data", (data: any) => {
          const text = data.toString();
          text.split("\n").forEach((line: string) => {
            const trimmed = line.trim();
            if (trimmed) {
              appendLog(`[ERROR] ${trimmed}`);
            }
          });
        });

        botProcess.on("close", (code: any) => {
          appendLog(`sbt process exited with code ${code}`);
          botStatus = "stopped";
          botProcess = null;
        });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: botStatus }));
        return;
      }

      if (req.url === "/api/dev-bot/stop" && req.method === "POST") {
        if (!botProcess) {
          botStatus = "stopped";
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ status: botStatus }));
          return;
        }

        botStatus = "stopping";
        appendLog("Stopping sbt process...");

        const pid = botProcess.pid;
        const killCmd = process.platform === "win32" ? `taskkill /pid ${pid} /T /F` : `kill -9 ${pid}`;

        exec(killCmd, (err) => {
          if (err) {
            appendLog(`Failed to kill process tree: ${err.message}`);
          }
          botStatus = "stopped";
          botProcess = null;
        });

        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: botStatus }));
        return;
      }

      if (req.url === "/api/dev-bot/status" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            status: botStatus,
            logs: botLogs.slice(-100)
          })
        );
        return;
      }

      next();
    });
  }
});

export default defineConfig({
  plugins: [react(), devBotPlugin()],
  server: {
    port: 5173,
    strictPort: true
  }
});
