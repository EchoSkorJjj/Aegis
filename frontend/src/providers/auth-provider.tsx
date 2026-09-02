"use client";

import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";

import { authApi } from "@/lib/api";
import { assertPasskey } from "@/lib/webauthn";
import type {
  LoginInput,
  LoginResponse,
  UserProfile,
  VerifyOtpInput,
} from "@/lib/types";

interface AuthContextValue {
  user: UserProfile | null;
  isLoading: boolean;
  /**
   * Submits the password. Resolves with the backend outcome: `mfaRequired` means
   * the caller must collect the emailed code and call {@link verifyOtp}. On a
   * direct sign-in (2FA disabled) the session is established and the user is
   * redirected to the dashboard.
   */
  login: (input: LoginInput) => Promise<LoginResponse>;
  /** Completes 2FA with the emailed code, then signs in. */
  verifyOtp: (input: VerifyOtpInput) => Promise<void>;
  /** Completes 2FA with a passkey (WebAuthn assertion) for the pending challenge, then signs in. */
  loginWebauthn: (challengeId: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Re-reads the current user from the server (e.g. after a profile change). */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<UserProfile | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Ask the server who we are — the session lives in httpOnly cookies, so this
  // is the only way the client learns whether it is signed in.
  const loadUser = useCallback(async () => {
    try {
      setUser(await authApi.me());
    } catch {
      setUser(null);
    }
  }, []);

  useEffect(() => {
    loadUser().finally(() => setIsLoading(false));
  }, [loadUser]);

  const completeSignIn = useCallback(async () => {
    setUser(await authApi.me());
    router.push("/dashboard");
    router.refresh(); // re-run the proxy/layout now that cookies are set
  }, [router]);

  const login = useCallback(
    async (input: LoginInput) => {
      const result = await authApi.login(input);
      if (!result.mfaRequired) {
        await completeSignIn();
      }
      return result;
    },
    [completeSignIn],
  );

  const verifyOtp = useCallback(
    async (input: VerifyOtpInput) => {
      await authApi.verifyOtp(input);
      await completeSignIn();
    },
    [completeSignIn],
  );

  const loginWebauthn = useCallback(
    async (challengeId: string) => {
      await assertPasskey(challengeId);
      await completeSignIn();
    },
    [completeSignIn],
  );

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Best-effort — the cookies are cleared server-side regardless.
    }
    setUser(null);
    router.push("/login");
    router.refresh();
  }, [router]);

  const value = useMemo(
    () => ({
      user,
      isLoading,
      login,
      verifyOtp,
      loginWebauthn,
      logout,
      refresh: loadUser,
    }),
    [user, isLoading, login, verifyOtp, loginWebauthn, logout, loadUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an <AuthProvider>");
  }
  return context;
}
