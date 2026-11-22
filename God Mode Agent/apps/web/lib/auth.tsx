"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import {
  clearTokens,
  login as apiLogin,
  logout as apiLogout,
  onLogout,
  setTokens,
} from "@/lib/api";

export type User = {
  id: string;
  username: string;
  role: string;
  tenant: string;
};

type AuthState = {
  user: User | null;
  loginUser: (u: string, p: string) => Promise<string | null>;
  logoutUser: () => Promise<void>;
  hasRole: (min: string) => boolean;
};

const ROLES = ["readonly", "user", "operator", "admin"];

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);

  useEffect(() => {
    onLogout(() => setUser(null));
  }, []);

  const loginUser = useCallback(async (username: string, password: string) => {
    const resp = await apiLogin(username, password);
    if (resp.success) {
      setTokens(resp.data.access_token, resp.data.refresh_token);
      setUser(resp.data.user);
      return null;
    }
    return `${resp.error.code}: ${resp.error.message}`;
  }, []);

  const logoutUser = useCallback(async () => {
    await apiLogout();
    clearTokens();
    setUser(null);
  }, []);

  const hasRole = useCallback(
    (min: string) => {
      if (!user) return false;
      return ROLES.indexOf(user.role) >= ROLES.indexOf(min);
    },
    [user],
  );

  const value = useMemo(
    () => ({ user, loginUser, logoutUser, hasRole }),
    [user, loginUser, logoutUser, hasRole],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
