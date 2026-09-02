export type Role = "USER" | "ADMIN";

/** Second-factor methods the backend may offer. Mirrors the backend MfaMethod enum. */
export type MfaMethod = "EMAIL_OTP" | "TOTP" | "WEBAUTHN";

/** Public auth configuration from GET /api/auth/config — one backend flag drives the whole UI. */
export interface AuthConfig {
  mode: string;
  registrationEnabled: boolean;
  methods: { emailOtp: boolean; totp: boolean; webauthn: boolean };
}

/** Mirrors the backend UserResponse. */
export interface UserProfile {
  id: string;
  email: string;
  displayName: string | null;
  roles: Role[];
  enabled: boolean;
  emailVerified: boolean;
  createdAt: string;
}

/** Mirrors the backend TokenResponse from POST /auth/login. */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface LoginInput {
  email: string;
  password: string;
}

export interface RegisterInput {
  email: string;
  password: string;
  displayName?: string;
}

/**
 * Response from POST /api/auth/login (our BFF). When `mfaRequired` is true the
 * password was correct but a second factor (emailed OTP) is still needed, and
 * `challengeId` identifies the pending challenge — no session is established yet.
 */
export interface LoginResponse {
  mfaRequired: boolean;
  challengeId?: string;
  /** The armed second factor (which input to show); absent only if the user must still choose. */
  method?: MfaMethod;
  /** Every method available for this login — the basis for a chooser when there is more than one. */
  methods?: MfaMethod[];
}

/** Second factor: the code plus the challenge it answers (email OTP or authenticator). */
export interface VerifyOtpInput {
  challengeId: string;
  code: string;
}

/** A pending second factor — the armed `method` plus every method the user may switch to. */
export interface MfaChallenge {
  challengeId: string;
  methods: MfaMethod[];
  method: MfaMethod;
}

export interface SelectMethodInput {
  challengeId: string;
  method: MfaMethod;
}

/** Authenticator enrollment start: the raw secret (manual entry) + otpauth URI (QR code). */
export interface TotpInit {
  secret: string;
  otpauthUri: string;
}

export interface MfaStatus {
  totpEnabled: boolean;
  recoveryCodesRemaining: number;
}

/** The plaintext backup codes, returned once (on enrollment or regeneration). */
export interface RecoveryCodes {
  recoveryCodes: string[];
}

/** A registered passkey (WebAuthn credential) shown on the settings page. */
export interface Passkey {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface ForgotPasswordInput {
  email: string;
}

export interface ResetPasswordInput {
  token: string;
  newPassword: string;
}

/** Accept an admin invite: set the first password with the emailed token. */
export interface AcceptInviteInput {
  token: string;
  newPassword: string;
}

/** Admin-provisions an account; the backend emails an invite link. */
export interface InviteUserInput {
  email: string;
  displayName?: string;
}

export interface UpdateProfileInput {
  displayName: string;
}

export interface ChangePasswordInput {
  currentPassword: string;
  newPassword: string;
}

/** The subset of a Spring Data `Page` the UI consumes (`number` is the 0-based page index). */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}
