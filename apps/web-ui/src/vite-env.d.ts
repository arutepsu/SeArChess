/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_API_PATH_PREFIX?: string;
  readonly VITE_DEV_PROXY_TARGET?: string;
  readonly VITE_ANALYTICS_API_BASE_URL?: string;
  readonly VITE_ANALYTICS_PROXY_TARGET?: string;
  readonly VITE_TOURNAMENT_API_BASE_URL?: string;
  readonly VITE_TOURNAMENT_PROXY_TARGET?: string;
  readonly VITE_AUTH_ENABLED?: string;
  readonly VITE_LICHESS_BOT_WS_URL?: string;
  readonly VITE_WS_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.css";
