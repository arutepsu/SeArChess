import { useCallback, useEffect, useRef, useState } from "react";

export type StreamStatus = "idle" | "connecting" | "open" | "closed" | "error";

export interface UseNDJSONStreamResult<T> {
  events: T[];
  latestEvent: T | null;
  status: StreamStatus;
  error: string | null;
  connect: () => void;
  disconnect: () => void;
}

export interface UseNDJSONStreamOptions {
  /** Max number of events to keep in memory. Older events are dropped. Default: 500. */
  maxEvents?: number;
  /** Connect on mount. Default: true. */
  autoConnect?: boolean;
}

/**
 * Reads an NDJSON stream via fetch + ReadableStream.
 *
 * Accepts an async `getRequest` function so auth headers can be refreshed
 * (e.g. via Keycloak token refresh) before each connection attempt.
 *
 * Does not use WebSocket. Does not attempt automatic reconnection.
 */
export function useNDJSONStream<T>(
  getRequest: (() => Promise<{ url: string; headers: Record<string, string> }>) | null,
  options: UseNDJSONStreamOptions = {},
): UseNDJSONStreamResult<T> {
  const { maxEvents = 500, autoConnect = true } = options;

  const [events, setEvents] = useState<T[]>([]);
  const [latestEvent, setLatestEvent] = useState<T | null>(null);
  const [status, setStatus] = useState<StreamStatus>("idle");
  const [error, setError] = useState<string | null>(null);

  const controllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);

  // Keep a ref to the latest getRequest so connect() doesn't need it as a dep
  const getRequestRef = useRef(getRequest);
  useEffect(() => {
    getRequestRef.current = getRequest;
  }, [getRequest]);

  const disconnect = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, []);

  const connect = useCallback(() => {
    const getReq = getRequestRef.current;
    if (!getReq) return;

    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    if (!mountedRef.current) return;
    setStatus("connecting");
    setError(null);
    setEvents([]);
    setLatestEvent(null);

    void (async () => {
      try {
        const { url, headers } = await getReq();

        const response = await fetch(url, {
          headers,
          signal: controller.signal,
        });

        if (!mountedRef.current) return;

        if (!response.ok) {
          const text = await response.text().catch(() => "");
          let message = `Stream request failed: ${response.status}`;
          try {
            const json = JSON.parse(text) as { message?: string; code?: string };
            if (json.message) message = json.message;
          } catch { /* ignore */ }
          setStatus("error");
          setError(message);
          return;
        }

        if (!response.body) {
          setStatus("error");
          setError("No response body from server");
          return;
        }

        setStatus("open");

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!mountedRef.current) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() ?? "";

          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed) continue; // heartbeat / empty line
            try {
              const event = JSON.parse(trimmed) as T;
              if (mountedRef.current) {
                setEvents((prev) => {
                  const next = [...prev, event];
                  return next.length > maxEvents ? next.slice(next.length - maxEvents) : next;
                });
                setLatestEvent(event);
              }
            } catch {
              // malformed NDJSON line — skip silently
            }
          }
        }

        if (mountedRef.current) {
          setStatus("closed");
        }
      } catch (err) {
        if (!mountedRef.current) return;
        if (err instanceof Error && err.name === "AbortError") {
          setStatus("closed");
          return;
        }
        setStatus("error");
        setError(err instanceof Error ? err.message : "Stream connection failed");
      }
    })();
  }, [maxEvents]);

  useEffect(() => {
    mountedRef.current = true;
    if (autoConnect && getRequest !== null) {
      connect();
    }
    return () => {
      mountedRef.current = false;
      disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { events, latestEvent, status, error, connect, disconnect };
}
