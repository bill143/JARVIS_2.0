export const BACKEND =
  process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://127.0.0.1:8000";

// --- In-memory token store (NEVER localStorage: auth state stays in memory,
// so a full page reload requires re-login, per the frontend security rules). ---
type Tokens = { access: string; refresh: string } | null;
let _tokens: Tokens = null;
let _onLogout: (() => void) | null = null;

export function setTokens(access: string, refresh: string) {
  _tokens = { access, refresh };
}
export function clearTokens() {
  _tokens = null;
}
export function hasTokens() {
  return _tokens !== null;
}
export function onLogout(fn: () => void) {
  _onLogout = fn;
}

export const wsUrl = (path: string) => {
  const base = BACKEND.replace(/^http/, "ws") + path;
  return _tokens ? `${base}?token=${encodeURIComponent(_tokens.access)}` : base;
};

export type ApiError = {
  code: string;
  message: string;
  requestId: string;
  approval_id?: string;
};
export type Envelope<T> =
  { success: true; data: T } | { success: false; error: ApiError };

export type ToolEvent = {
  tool: string;
  status: "ok" | "error";
  summary: string;
  duration_ms: number;
};

async function refreshAccess(): Promise<boolean> {
  if (!_tokens) return false;
  try {
    const resp = await fetch(`${BACKEND}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: _tokens.refresh }),
    });
    const body = await resp.json();
    if (body.success) {
      _tokens = {
        access: body.data.access_token,
        refresh: body.data.refresh_token,
      };
      return true;
    }
  } catch {
    /* fall through */
  }
  return false;
}

async function authedFetch(
  path: string,
  init: RequestInit,
  retry = true,
): Promise<Response> {
  const headers = new Headers(init.headers || {});
  if (_tokens) headers.set("Authorization", `Bearer ${_tokens.access}`);
  const resp = await fetch(`${BACKEND}${path}`, { ...init, headers });
  if (resp.status === 401 && retry && _tokens) {
    if (await refreshAccess()) return authedFetch(path, init, false);
    clearTokens();
    _onLogout?.();
  }
  return resp;
}

export async function postJson<T>(
  path: string,
  body: unknown,
  extraHeaders?: Record<string, string>,
): Promise<Envelope<T>> {
  try {
    const resp = await authedFetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(extraHeaders || {}) },
      body: JSON.stringify(body),
    });
    return (await resp.json()) as Envelope<T>;
  } catch (err) {
    return {
      success: false,
      error: {
        code: "NETWORK_ERROR",
        message: String(err),
        requestId: "client",
      },
    };
  }
}

export async function getJson<T>(path: string): Promise<Envelope<T>> {
  try {
    const resp = await authedFetch(path, { method: "GET" });
    return (await resp.json()) as Envelope<T>;
  } catch (err) {
    return {
      success: false,
      error: {
        code: "NETWORK_ERROR",
        message: String(err),
        requestId: "client",
      },
    };
  }
}

// Unauthenticated login (no token attached).
export async function login(
  username: string,
  password: string,
): Promise<Envelope<any>> {
  try {
    const resp = await fetch(`${BACKEND}/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    return (await resp.json()) as Envelope<any>;
  } catch (err) {
    return {
      success: false,
      error: {
        code: "NETWORK_ERROR",
        message: String(err),
        requestId: "client",
      },
    };
  }
}

export async function logout() {
  if (_tokens) {
    try {
      await fetch(`${BACKEND}/auth/logout`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: _tokens.refresh }),
      });
    } catch {
      /* ignore */
    }
  }
  clearTokens();
}

export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      resolve(result.substring(result.indexOf(",") + 1));
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

export function blobToBase64(blob: Blob): Promise<string> {
  return fileToBase64(blob as File);
}
