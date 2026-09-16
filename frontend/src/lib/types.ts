export type Role =
  | "USER"
  | "PLATFORM_ADMIN"
  | "ORG_ADMIN"
  | "SERVICE_OWNER"
  | "ANALYST"
  | "COMPLIANCE_ADMIN";

/** Second-factor methods the backend may offer. Mirrors the backend MfaMethod enum. */
export type MfaMethod = "EMAIL_OTP" | "TOTP" | "WEBAUTHN";

/** Public auth configuration from GET /api/auth/config — one backend flag drives the whole UI. */
export interface AuthConfig {
  mode: string;
  registrationEnabled: boolean;
  /** Whether a signed-in user may create an organization (ADR 0001; gates the "Create organization" UI). */
  organizationSignupEnabled: boolean;
  methods: { emailOtp: boolean; totp: boolean; webauthn: boolean };
}

/** Lifecycle of an organization membership. Mirrors the backend MembershipStatus. */
export type MembershipStatus = "INVITED" | "ACTIVE" | "SUSPENDED";

/** One organization the current user belongs to (from GET /api/orgs) — drives the workspace switcher. */
export interface Membership {
  /** The organization's id. */
  id: string;
  name: string;
  /** The caller's roles within this organization. */
  roles: Role[];
  status: MembershipStatus;
}

/**
 * Mirrors the backend UserResponse. `roles` are the caller's effective roles in their active org
 * (platform roles plus that org's membership roles); `orgId` is the active org, null when the user
 * belongs to no organization yet.
 */
export interface UserProfile {
  id: string;
  email: string;
  displayName: string | null;
  roles: Role[];
  /** The active organization (tenant); null for a user who has not created or joined one. */
  orgId: string | null;
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

/** Admin-provisions an account into the caller's org; the backend emails an invite link (new emails). */
export interface InviteUserInput {
  email: string;
  displayName?: string;
  /** Org roles granted on top of USER (org admins may not assign platform-level roles). */
  roles?: Role[];
}

/** Create a new organization the caller will own (POST /api/orgs). */
export interface CreateOrgInput {
  name: string;
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
