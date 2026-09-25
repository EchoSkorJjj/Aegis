import type {
  AcceptInviteInput,
  AuthConfig,
  BusinessService,
  BusinessServiceInput,
  ChangeImpactResponse,
  ChangePasswordInput,
  ChangeMatchResponse,
  ChangeSignal,
  ChangeSignalFacets,
  ComponentImpact,
  CreateOrgInput,
  ForgotPasswordInput,
  InviteUserInput,
  LoginInput,
  LoginResponse,
  Membership,
  MfaChallenge,
  MfaStatus,
  DirectoryMember,
  Owner,
  Page,
  Passkey,
  RecoveryCodes,
  RegisterInput,
  ResetPasswordInput,
  Role,
  SbomImportResult,
  SelectMethodInput,
  SoftwareComponent,
  SoftwareComponentInput,
  ThirdPartyVendor,
  TotpInit,
  UpdateProfileInput,
  UserProfile,
  VendorImpact,
  VerifyOtpInput,
} from "@/lib/types";
import type {
  AuthenticationResponseJSON,
  PublicKeyCredentialCreationOptionsJSON,
  PublicKeyCredentialRequestOptionsJSON,
  RegistrationResponseJSON,
} from "@simplewebauthn/browser";

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

/** Build a query string from defined, non-empty params (leading "?" included, or "" when empty). */
function qs(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") search.set(key, String(value));
  }
  const query = search.toString();
  return query ? `?${query}` : "";
}

/** Query params common to the paginated + filterable list endpoints. `sort` is Spring's "field,dir". */
type ListParams = {
  page?: number;
  size?: number;
  search?: string;
  from?: string;
  to?: string;
  sort?: string;
};

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
  /** Passkey login: request assertion options for the pending challenge. */
  webauthnLoginOptions(input: { challengeId: string }) {
    return bffFetch<PublicKeyCredentialRequestOptionsJSON>(
      "/api/auth/login/webauthn/options",
      { method: "POST", body: JSON.stringify(input) },
    );
  },
  /** Passkey login: verify the signed assertion and establish the session. */
  webauthnLoginVerify(input: {
    challengeId: string;
    credential: AuthenticationResponseJSON;
  }) {
    return bffFetch<{ ok: true }>("/api/auth/login/webauthn/verify", {
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
  /** Switch the active organization (ADR 0001). The BFF re-issues + re-sets the session cookies. */
  switchOrg(orgId: string) {
    return bffFetch<void>("/api/auth/switch-org", {
      method: "POST",
      body: JSON.stringify({ orgId }),
    });
  },
};

/** The caller's organizations + self-serve org creation (workspace switcher). */
export const orgApi = {
  /** Every organization the caller belongs to, with their roles in each. */
  list() {
    return bffFetch<Membership[]>("/api/orgs");
  },
  /** Create an organization the caller will own (ORG_ADMIN). */
  create(input: CreateOrgInput) {
    return bffFetch<Membership>("/api/orgs", {
      method: "POST",
      body: JSON.stringify(input),
    });
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

/** Admin user-management actions (proxied through the BFF; the backend enforces org/platform admin roles). */
export const adminApi = {
  inviteUser(input: InviteUserInput) {
    return bffFetch<UserProfile>("/api/admin/users", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
};

/**
 * The active organization's member directory (identity-service `GET /users`, read-only for any member
 * and scoped to the active org server-side). `role` narrows to members holding that role in this org —
 * e.g. SERVICE_OWNER for the owner picker.
 */
export const directoryApi = {
  listMembers(params: { search?: string; role?: Role; page?: number; size?: number } = {}) {
    return bffFetch<Page<DirectoryMember>>(`/api/users${qs(params)}`);
  },
};

/**
 * The estate map (aegis-core inventory), proxied through the BFF. Scoped to the caller's active
 * organization server-side; the backend enforces the domain read/write roles (403 passed through).
 */
export const inventoryApi = {
  listServices(params: ListParams & { criticality?: string; exposure?: string } = {}) {
    return bffFetch<Page<BusinessService>>(`/api/inventory/services${qs(params)}`);
  },
  getService(id: string) {
    return bffFetch<BusinessService>(`/api/inventory/services/${id}`);
  },
  createService(input: BusinessServiceInput) {
    return bffFetch<BusinessService>("/api/inventory/services", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  updateService(id: string, input: BusinessServiceInput) {
    return bffFetch<BusinessService>(`/api/inventory/services/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
    });
  },
  deleteService(id: string) {
    return bffFetch<void>(`/api/inventory/services/${id}`, { method: "DELETE" });
  },
  linkServiceRepository(serviceId: string, url: string, branch?: string) {
    return bffFetch<BusinessService>(`/api/inventory/services/${serviceId}/repository`, {
      method: "PUT",
      body: JSON.stringify({ url, branch: branch || null }),
    });
  },
  unlinkServiceRepository(serviceId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/repository`, { method: "DELETE" });
  },

  listComponents(params: ListParams & { ecosystem?: string; excludeLinkedToService?: string } = {}) {
    return bffFetch<Page<SoftwareComponent>>(`/api/inventory/components${qs(params)}`);
  },
  /** Distinct component ecosystems in the caller's org — powers the ecosystem filter select. */
  componentEcosystems() {
    return bffFetch<string[]>("/api/inventory/components/ecosystems");
  },
  createComponent(input: SoftwareComponentInput) {
    return bffFetch<SoftwareComponent>("/api/inventory/components", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  updateComponent(id: string, input: SoftwareComponentInput) {
    return bffFetch<SoftwareComponent>(`/api/inventory/components/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
    });
  },
  deleteComponent(id: string) {
    return bffFetch<void>(`/api/inventory/components/${id}`, { method: "DELETE" });
  },

  listVendors(params: ListParams & { excludeLinkedToService?: string } = {}) {
    return bffFetch<Page<ThirdPartyVendor>>(`/api/inventory/vendors${qs(params)}`);
  },
  createVendor(input: { name: string }) {
    return bffFetch<ThirdPartyVendor>("/api/inventory/vendors", {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  updateVendor(id: string, input: { name: string }) {
    return bffFetch<ThirdPartyVendor>(`/api/inventory/vendors/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
    });
  },
  deleteVendor(id: string) {
    return bffFetch<void>(`/api/inventory/vendors/${id}`, { method: "DELETE" });
  },

  // ---- Owners (organization members holding SERVICE_OWNER, assigned to services) ------------
  /** The people currently assigned as an owner of at least one service. Read-only — owners are
   *  created by assigning a member to a service, never by name. */
  listOwners(params: ListParams & { excludeLinkedToService?: string } = {}) {
    return bffFetch<Page<Owner>>(`/api/inventory/owners${qs(params)}`);
  },

  // ---- Service composition: the components / vendors / owners linked to one service ---------
  listServiceComponents(serviceId: string, params: { page?: number; size?: number } = {}) {
    return bffFetch<Page<SoftwareComponent>>(`/api/inventory/services/${serviceId}/components${qs(params)}`);
  },
  addServiceComponent(serviceId: string, componentId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/components/${componentId}`, { method: "POST" });
  },
  removeServiceComponent(serviceId: string, componentId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/components/${componentId}`, { method: "DELETE" });
  },
  listServiceVendors(serviceId: string, params: { page?: number; size?: number } = {}) {
    return bffFetch<Page<ThirdPartyVendor>>(`/api/inventory/services/${serviceId}/vendors${qs(params)}`);
  },
  addServiceVendor(serviceId: string, vendorId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/vendors/${vendorId}`, { method: "POST" });
  },
  removeServiceVendor(serviceId: string, vendorId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/vendors/${vendorId}`, { method: "DELETE" });
  },
  listServiceOwners(serviceId: string, params: { page?: number; size?: number } = {}) {
    return bffFetch<Page<Owner>>(`/api/inventory/services/${serviceId}/owners${qs(params)}`);
  },
  /** Assign an org member (by identity user id) as a service owner. The backend verifies they are a
   *  member of the active org holding SERVICE_OWNER. Governance roles only. */
  assignServiceOwner(serviceId: string, userId: string) {
    return bffFetch<Owner>(`/api/inventory/services/${serviceId}/owners`, {
      method: "POST",
      body: JSON.stringify({ userId }),
    });
  },
  removeServiceOwner(serviceId: string, ownerId: string) {
    return bffFetch<void>(`/api/inventory/services/${serviceId}/owners/${ownerId}`, { method: "DELETE" });
  },

  /**
   * Import a service's components and dependency edges from a CycloneDX JSON SBOM. `cyclonedxJson`
   * is the raw file text; the backend parses it, is additive + idempotent, and returns a summary.
   */
  importSbom(serviceId: string, cyclonedxJson: string) {
    return bffFetch<SbomImportResult>(`/api/inventory/services/${serviceId}/sbom`, {
      method: "POST",
      body: cyclonedxJson,
    });
  },

  /**
   * Generate a CycloneDX SBOM from a Git repository (the backend shallow-clones it and runs syft) and
   * import it into the service. `ref` is an optional branch/tag; `token` is an optional access token for
   * a private repository (used transiently for the clone, never stored).
   */
  generateSbomFromRepo(serviceId: string, repoUrl: string, ref?: string, token?: string) {
    return bffFetch<SbomImportResult>(`/api/inventory/services/${serviceId}/sbom/from-repo`, {
      method: "POST",
      body: JSON.stringify({
        repoUrl,
        ref: ref?.trim() || undefined,
        token: token?.trim() || undefined,
      }),
    });
  },

  /** Services + owners impacted by a component, including those reached transitively. */
  componentImpact(id: string) {
    return bffFetch<ComponentImpact>(`/api/inventory/components/${id}/impact`);
  },
  /** Services + owners impacted by a vendor (direct dependency only). */
  vendorImpact(id: string) {
    return bffFetch<VendorImpact>(`/api/inventory/vendors/${id}/impact`);
  },
};

/** The unified change-signals feed (advisories + vendor outages + security news), read-only. */
export const signalApi = {
  list(
    params: ListParams & { type?: string; severity?: string; source?: string; ecosystem?: string } = {},
  ) {
    return bffFetch<Page<ChangeSignal>>(`/api/change-signals${qs(params)}`);
  },
  /** Distinct sources + severities + ecosystems present — powers the advisory filter selects. */
  facets() {
    return bffFetch<ChangeSignalFacets>("/api/change-signals/facets");
  },
};

/**
 * Epic 6 matching: contextualize one change signal against the caller's active-org estate — the
 * impacted services, their owners, and the evidence chain for each. Read-only; org-scoped server-side.
 */
export const matchApi = {
  matchChange(signalId: string) {
    return bffFetch<ChangeMatchResponse>(`/api/matching/change-signals/${signalId}`);
  },
};

/** aegis-core impact scoring (Epic 7) — read-only, recomputed per request. */
export const scoringApi = {
  scoreChange(signalId: string) {
    return bffFetch<ChangeImpactResponse>(`/api/scoring/change-signals/${signalId}`);
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
  /** Begin passkey registration: WebAuthn creation options for the authenticator. */
  webauthnRegisterBegin() {
    return bffFetch<PublicKeyCredentialCreationOptionsJSON>(
      "/api/auth/mfa/webauthn/register/begin",
      { method: "POST" },
    );
  },
  /** Finish passkey registration: send the attestation, store the credential. */
  webauthnRegisterFinish(input: {
    label: string;
    credential: RegistrationResponseJSON;
  }) {
    return bffFetch<{ credentialId: string; label: string; recoveryCodes: string[] }>(
      "/api/auth/mfa/webauthn/register/finish",
      { method: "POST", body: JSON.stringify(input) },
    );
  },
  /** The caller's registered passkeys. */
  webauthnList() {
    return bffFetch<Passkey[]>("/api/auth/mfa/webauthn/credentials");
  },
  /** Remove one of the caller's passkeys by id. */
  webauthnDelete(id: string) {
    return bffFetch<void>(`/api/auth/mfa/webauthn/credentials/${id}`, {
      method: "DELETE",
    });
  },
};
