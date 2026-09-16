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

import { authApi, orgApi } from "@/lib/api";
import { assertPasskey } from "@/lib/webauthn";
import type {
  LoginInput,
  LoginResponse,
  Membership,
  UserProfile,
  VerifyOtpInput,
} from "@/lib/types";

interface AuthContextValue {
  user: UserProfile | null;
  /** The organizations the current user belongs to (drives the workspace switcher). */
  organizations: Membership[];
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
  /** Switch the active organization (ADR 0001) and re-load the session. */
  switchOrg: (orgId: string) => Promise<void>;
  /** Create a new organization, become its owner, and switch into it. */
  createOrg: (name: string) => Promise<void>;
  /** Re-reads the current user from the server (e.g. after a profile change). */
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<UserProfile | null>(null);
  const [organizations, setOrganizations] = useState<Membership[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Ask the server who we are — the session lives in httpOnly cookies, so this
  // is the only way the client learns whether it is signed in. When signed in we
  // also load the caller's organizations for the workspace switcher.
  const loadUser = useCallback(async () => {
    try {
      setUser(await authApi.me());
      try {
        setOrganizations(await orgApi.list());
      } catch {
        setOrganizations([]);
      }
    } catch {
      setUser(null);
      setOrganizations([]);
    }
  }, []);

  useEffect(() => {
    // Bootstrap the session from the server on mount: it lives in httpOnly cookies, so the client
    // must ask who it is. This is a legitimate external-system sync, and the state updates happen in
    // async callbacks (not synchronously in the effect body) — the heuristic below is a false positive.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadUser().finally(() => setIsLoading(false));
  }, [loadUser]);

  const completeSignIn = useCallback(async () => {
    await loadUser();
    router.push("/dashboard");
    router.refresh(); // re-run the proxy/layout now that cookies are set
  }, [loadUser, router]);

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
    setOrganizations([]);
    router.push("/login");
    router.refresh();
  }, [router]);

  const switchOrg = useCallback(
    async (orgId: string) => {
      await authApi.switchOrg(orgId);
      await loadUser();
      router.refresh();
    },
    [loadUser, router],
  );

  const createOrg = useCallback(
    async (name: string) => {
      const org = await orgApi.create({ name });
      await authApi.switchOrg(org.id); // scope the session to the new org
      await loadUser();
      router.refresh();
    },
    [loadUser, router],
  );

  const value = useMemo(
    () => ({
      user,
      organizations,
      isLoading,
      login,
      verifyOtp,
      loginWebauthn,
      logout,
      switchOrg,
      createOrg,
      refresh: loadUser,
    }),
    [
      user,
      organizations,
      isLoading,
      login,
      verifyOtp,
      loginWebauthn,
      logout,
      switchOrg,
      createOrg,
      loadUser,
    ],
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
