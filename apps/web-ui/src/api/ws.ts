import type { WsEvent } from "./wsTypes";

const DEFAULT_WS_BASE = "ws://localhost:10000/ws";

export const wsBaseUrl =
  import.meta.env.VITE_WS_URL?.toString() || DEFAULT_WS_BASE;

export interface WsClient {
  close: () => void;
}

export function connectWebSocket(handlers: {
<<<<<<< HEAD
<<<<<<< HEAD
  gameId: string;
  getSessionId?: () => string | null;
=======
  getSessionId: () => string | null;
>>>>>>> ce08c01e (local microservices)
=======
  gameId: string;
  getSessionId?: () => string | null;
>>>>>>> 3bfa20a2 (polish web ui)
  onOpen?: () => void;
  onClose?: () => void;
  onError?: (event: Event) => void;
  onMessage?: (event: WsEvent) => void;
}): WsClient {
  const baseUrl = wsBaseUrl.replace(/\/$/, "");
  const socket = new WebSocket(`${baseUrl}/games/${encodeURIComponent(handlers.gameId)}`);

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
<<<<<<< HEAD
<<<<<<< HEAD
      if (parsed.gameId !== handlers.gameId) {
        return;
      }

      const sessionId = handlers.getSessionId?.();
      if (sessionId && parsed.sessionId !== sessionId) {
=======
      const sessionId = handlers.getSessionId();

      if (!sessionId) {
        return;
      }

      if (parsed.sessionId !== sessionId) {
>>>>>>> ce08c01e (local microservices)
=======
      if (parsed.gameId !== handlers.gameId) {
        return;
      }

      const sessionId = handlers.getSessionId?.();
      if (sessionId && parsed.sessionId !== sessionId) {
>>>>>>> 3bfa20a2 (polish web ui)
        return;
      }

      handlers.onMessage?.(parsed);
    } catch {
      // ignore malformed messages
    }
  };

  return {
    close: () => {
      if (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    }
  };
}
