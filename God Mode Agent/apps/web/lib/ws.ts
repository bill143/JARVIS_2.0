import { useEffect, useRef, useState } from "react";

export type JarvisMessage =
  | { type: "log"; speaker: string; text: string }
  | { type: "status"; state: "active" | "sleeping" }
  | { type: "sys"; text: string }
  | { type: "file_received"; name: string; size: number }
  | { type: "metrics"; cpu: number; ram: number; gpu: number; netUp: number; netDown: number };

export function parseJarvisMessage(raw: string): JarvisMessage | null {
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    switch (parsed.type) {
      case "log":
        return {
          type: "log",
          speaker: typeof parsed.speaker === "string" ? parsed.speaker : "system",
          text: typeof parsed.text === "string" ? parsed.text : "",
        };
      case "status":
        return {
          type: "status",
          state: parsed.state === "sleeping" ? "sleeping" : "active",
        };
      case "sys":
        return { type: "sys", text: typeof parsed.text === "string" ? parsed.text : "" };
      case "file_received":
        return {
          type: "file_received",
          name: typeof parsed.name === "string" ? parsed.name : "unknown",
          size: typeof parsed.size === "number" ? parsed.size : 0,
        };
      case "metrics":
        return {
          type: "metrics",
          cpu: typeof parsed.cpu === "number" ? parsed.cpu : 0,
          ram: typeof parsed.ram === "number" ? parsed.ram : 0,
          gpu: typeof parsed.gpu === "number" ? parsed.gpu : 0,
          netUp: typeof parsed.netUp === "number" ? parsed.netUp : 0,
          netDown: typeof parsed.netDown === "number" ? parsed.netDown : 0,
        };
      default:
        return null;
    }
  } catch {
    return null;
  }
}

export function useJarvisSocket(
  url: string,
  onMessage?: (message: JarvisMessage) => void,
): { socket: WebSocket | null; connected: boolean; reconnecting: boolean } {
  const [connected, setConnected] = useState(false);
  const [reconnecting, setReconnecting] = useState(false);
  const socketRef = useRef<WebSocket | null>(null);
  const attemptRef = useRef(0);

  useEffect(() => {
    let cancelled = false;
    let timeoutId: number | undefined;

    const connect = () => {
      if (cancelled) return;
      const ws = new WebSocket(url);
      socketRef.current = ws;
      setConnected(false);
      setReconnecting(true);

      ws.onopen = () => {
        if (cancelled) return;
        setConnected(true);
        setReconnecting(false);
        attemptRef.current = 0;
      };

      ws.onclose = () => {
        if (cancelled) return;
        setConnected(false);
        const retry = Math.min(30000, 1000 * 2 ** attemptRef.current);
        attemptRef.current += 1;
        setReconnecting(true);
        timeoutId = window.setTimeout(connect, retry);
      };

      ws.onerror = () => {
        if (cancelled) return;
        setReconnecting(true);
      };

      ws.onmessage = (event) => {
        const message = parseJarvisMessage(event.data);
        if (!message) return;
        onMessage?.(message);
      };
    };

    connect();

    return () => {
      cancelled = true;
      if (timeoutId !== undefined) window.clearTimeout(timeoutId);
      socketRef.current?.close();
      socketRef.current = null;
    };
  }, [onMessage, url]);

  return { socket: socketRef.current, connected, reconnecting };
}
