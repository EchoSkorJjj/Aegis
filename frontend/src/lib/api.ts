import type {
  AcceptInviteInput,
  AuthConfig,
  ChangePasswordInput,
  ForgotPasswordInput,
  InviteUserInput,
  LoginInput,
  LoginResponse,
  MfaChallenge,
  MfaStatus,
  RecoveryCodes,
  RegisterInput,
  ResetPasswordInput,
  SelectMethodInput,
  TotpInit,
  UpdateProfileInput,
  UserProfile,
  VerifyOtpInput,
} from "@/lib/types";

/**
 * Client-side API. The browser talks ONLY to our same-origin BFF under
 * `/api/auth/*` (see `app/api/auth`), never to the identity-service backend
 * directly. Sessions live in httpOnly cookies that the browser attaches
 * automatically, so no tokens are read or stored in client JavaScript.
 */

/** Thrown for any non-2xx response; carries the HTTP status + backend message. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function bffFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init.headers,
    },
  });

  if (!response.ok) {
    let message = response.statusText;
    try {
      const body = (await response.json()) as { message?: string };
      if (body?.message) message = body.message;
    } catch {
      // Non-JSON body — fall back to the status text.
    }
    throw new ApiError(response.status, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const authApi = {
  /** Public: login mode + which MFA methods are enabled (drives the login/settings UI). */
  config() {
    return bffFetch<AuthConfig>("/api/auth/config");
  },
  register(input: RegisterInput) {
    return bffFetch<UserProfile>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  login(input: LoginInput) {
    return bffFetch<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  verifyOtp(input: VerifyOtpInput) {
    return bffFetch<{ ok: true }>("/api/auth/verify-otp", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  /** Chooser: switch the pending challenge to another enrolled factor (arms it). */
  selectMethod(input: SelectMethodInput) {
    return bffFetch<MfaChallenge>("/api/auth/login/select-method", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  logout() {
    return bffFetch<void>("/api/auth/logout", { method: "POST" });
  },
  me() {
    return bffFetch<UserProfile>("/api/auth/me");
  },
  forgotPassword(input: ForgotPasswordInput) {
    return bffFetch<void>("/api/auth/forgot-password", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  resetPassword(input: ResetPasswordInput) {
    return bffFetch<void>("/api/auth/reset-password", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  acceptInvite(input: AcceptInviteInput) {
    return bffFetch<void>("/api/auth/accept-invite", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  verifyEmail(token: string) {
    return bffFetch<void>("/api/auth/verify-email", {
      method: "POST",
      body: JSON.stringify({ token }),
    });
  },
  logoutAll() {
    return bffFetch<void>("/api/auth/logout-all", { method: "POST" });
  },
};

/** Authenticated self-service account actions (all proxied through the BFF). */
export const accountApi = {
  updateProfile(input: UpdateProfileInput) {
    return bffFetch<UserProfile>("/api/account/profile", {
      method: "PUT",
      body: JSON.stringify(input),
    });
  },
  changePassword(input: ChangePasswordInput) {
    return bffFetch<void>("/api/account/password", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  resendVerification() {
    return bffFetch<void>("/api/account/resend-verification", { method: "POST" });
  },
};

/** Admin user-management actions (proxied through the BFF; the backend enforces the ADMIN role). */
export const adminApi = {
  inviteUser(input: InviteUserInput) {
    return bffFetch<UserProfile>("/api/admin/users", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
};

/** Authenticated MFA self-enrollment (authenticator / TOTP). */
export const mfaApi = {
  status() {
    return bffFetch<MfaStatus>("/api/auth/mfa/status");
  },
  totpInit() {
    return bffFetch<TotpInit>("/api/auth/mfa/totp/init", { method: "POST" });
  },
  totpConfirm(code: string) {
    return bffFetch<RecoveryCodes>("/api/auth/mfa/totp/confirm", {
      method: "POST",
      body: JSON.stringify({ code }),
    });
  },
  totpDisable(code: string) {
    return bffFetch<void>("/api/auth/mfa/totp/disable", {
      method: "POST",
      body: JSON.stringify({ code }),
    });
  },
  regenerateRecoveryCodes(code: string) {
    return bffFetch<RecoveryCodes>("/api/auth/mfa/recovery-codes/regenerate", {
      method: "POST",
      body: JSON.stringify({ code }),
    });
  },
};
