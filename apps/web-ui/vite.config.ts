import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import http from "http";

const devBotPlugin = () => ({
  name: "dev-bot-runner",
  configureServer(server: any) {
    server.middlewares.use((req: any, res: any, next: any) => {
      if (req.url && req.url.startsWith("/api/bot/")) {
        let botPath = req.url;
        if (botPath === "/api/bot/start") botPath = "/api/bot/activate";
        if (botPath === "/api/bot/stop") botPath = "/api/bot/deactivate";

        const botProxyReq = http.request(
          {
            host: "localhost",
            port: 9324,
            path: botPath,
            method: req.method,
            headers: req.headers,
          },
          (botProxyRes) => {
            res.writeHead(botProxyRes.statusCode || 500, botProxyRes.headers);
            botProxyRes.pipe(res);
          }
        );

        botProxyReq.on("error", (botErr: any) => {
          // Both game-service and bot are offline, return stopped status with connection logs
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              status: "stopped",
              logs: [
                "Lichess Bot is offline or unreachable.",
                `lichess-bot (9324): ${botErr.message}`
              ],
            })
          );
        });

        if (req.method === "POST") {
          req.pipe(botProxyReq);
        } else {
          botProxyReq.end();
        }
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
