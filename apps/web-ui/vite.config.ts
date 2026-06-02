import { defineConfig, loadEnv, type ProxyOptions } from "vite";
import react from "@vitejs/plugin-react";

const backendProxyPaths = [
  "/api",
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
  const proxy: Record<string, string | ProxyOptions> = {};

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
