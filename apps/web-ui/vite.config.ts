import { defineConfig, loadEnv, type ProxyOptions } from "vite";
import react from "@vitejs/plugin-react";

const backendProxyPaths = [
  "/api",
  "/auth",
  "/sessions",
  "/games",
  "/notation",
  "/archive",
  "/archives",
  "/stats",
  "/health",
  "/admin/migrations",
  "/ws"
];

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxyTarget = env.VITE_DEV_PROXY_TARGET?.trim();
  const analyticsProxyTarget = env.VITE_ANALYTICS_PROXY_TARGET?.trim();
  const tournamentProxyTarget = env.VITE_TOURNAMENT_PROXY_TARGET?.trim();
  const proxy: Record<string, string | ProxyOptions> = {};

  // /api/analytics must be registered before /api to take precedence.
  if (analyticsProxyTarget) {
    proxy["/api/analytics"] = { target: analyticsProxyTarget, changeOrigin: true };
  }

  // /api/tournaments must be registered before /api to take precedence.
  if (tournamentProxyTarget) {
    proxy["/api/tournaments"] = { target: tournamentProxyTarget, changeOrigin: true };
  }

  if (proxyTarget) {
    for (const path of backendProxyPaths) {
      proxy[path] = {
        target: proxyTarget,
        changeOrigin: true,
        ws: path === "/ws"
      };
    }
  }

  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy
    }
  };
});
