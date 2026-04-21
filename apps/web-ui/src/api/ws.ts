import type { WsEvent } from "./wsTypes";

const DEFAULT_WS_BASE = "ws://localhost:10000/ws";

export const wsBaseUrl =
  import.meta.env.VITE_WS_URL?.toString() || DEFAULT_WS_BASE;

export interface WsClient {
  close: () => void;
}

export function connectWebSocket(handlers: {
  getSessionId: () => string | null;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: (event: Event) => void;
  onMessage?: (event: WsEvent) => void;
}): WsClient {
  const socket = new WebSocket(wsBaseUrl);

  socket.onopen = () => {
    handlers.onOpen?.();
  };

  socket.onclose = () => {
    handlers.onClose?.();
  };

  socket.onerror = (event) => {
    handlers.onError?.(event);
  };

  socket.onmessage = (messageEvent) => {
    try {
      const parsed = JSON.parse(messageEvent.data) as WsEvent;
      const sessionId = handlers.getSessionId();

      if (!sessionId) {
        return;
      }

      if (parsed.sessionId !== sessionId) {
        return;
      }

      handlers.onMessage?.(parsed);
    } catch {
      // ignore malformed messages
    }
  };

  return {
    close: () => socket.close()
  };
}
