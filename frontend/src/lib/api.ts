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
  CreateOrgInput,
  ForgotPasswordInput,
  GitHubConnectResult,
  GitHubIntegration,
  InviteUserInput,
  JiraIntegration,
  JiraOptions,
  LoginInput,
  LoginResponse,
  Membership,
  MfaChallenge,
  MfaStatus,
  DirectoryMember,
  Owner,
  OwnedService,
  PackageUsage,
  Page,
  Passkey,
  RecoveryCodes,
  RegisterInput,
  RegulatorMemo,
  RegulatorMemoStatus,
  ResetPasswordInput,
  RiskTicket,
  RiskTicketStatus,
  Role,
  SbomImportResult,
  SelectMethodInput,
  SoftwareComponent,
  SoftwareComponentInput,
  ThirdPartyVendor,
  TicketPriority,
  TotpInit,
  TriageQueueResponse,
  UpdateProfileInput,
  UpgradeProposal,
  UpgradeProposalStatus,
  UpgradeRecommendation,
  UserProfile,
  VendorUsage,
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
   * Generate the service's SBOM from its linked repository (the backend shallow-clones it and runs syft)
   * and import it. `token` is only for a private repository no host integration (the GitHub App) covers —
   * used for this clone, never stored — and `username` only where the host wants a particular one with it.
   */
  importSbomFromRepository(serviceId: string, credentials: { token?: string; username?: string } = {}) {
    return bffFetch<SbomImportResult>(`/api/inventory/services/${serviceId}/sbom/from-repository`, {
      method: "POST",
      body: JSON.stringify({
        token: credentials.token?.trim() || undefined,
        username: credentials.username?.trim() || undefined,
      }),
    });
  },

  /** Every service using a package — any version of it — directly or through which of its dependencies. */
  packageUsage(ecosystem: string, name: string) {
    const params = new URLSearchParams({ ecosystem, name });
    return bffFetch<PackageUsage>(`/api/inventory/components/used-by?${params}`);
  },
  /** Every service using a vendor, with its owners. */
  vendorUsage(id: string) {
    return bffFetch<VendorUsage>(`/api/inventory/vendors/${id}/used-by`);
  },
};

/** The unified change-signals feed (advisories + vendor outages + security news), read-only. */
export const signalApi = {
  get(id: string) {
    return bffFetch<ChangeSignal>(`/api/change-signals/${id}`);
  },
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

/**
 * The triage queue (E9 story 1): material vulnerable dependencies and vendor outages in progress, recomputed from
 * the active organization's estate; paging follows the ranking.
 */
export const findingApi = {
  list(
    params: {
      serviceId?: string;
      ownerId?: string;
      mine?: string;
      ecosystem?: string;
      tier?: string;
      page?: number;
      size?: number;
    } = {},
  ) {
    return bffFetch<TriageQueueResponse>(`/api/findings${qs(params)}`);
  },
  /** The services you are an assigned owner of, each with what it needs (E9 story 2). */
  myServices() {
    return bffFetch<OwnedService[]>("/api/findings/my-services");
  },
};

/** aegis-core impact scoring (Epic 7) — read-only, recomputed per request. */
export const scoringApi = {
  scoreChange(signalId: string) {
    return bffFetch<ChangeImpactResponse>(`/api/scoring/change-signals/${signalId}`);
  },
};

/**
 * Safe-upgrade pull requests (E8 story 4). One is drafted, and approved, as part of a response
 * (`ticketApi`); here it is read, and left out of its response by dismissing it with a reason. One drafted
 * on its own before responses existed (`standalone`) is still approved here. Org-scoped server-side; the
 * backend enforces who may decide (an analyst, or the service's assigned owners).
 */
export const upgradeApi = {
  list(
    params: {
      serviceId?: string;
      changeSignalId?: string;
      status?: UpgradeProposalStatus[];
      /** Only those drafted on their own, before responses. */
      standalone?: boolean;
      page?: number;
      size?: number;
    } = {},
  ) {
    const { status, standalone, ...rest } = params;
    return bffFetch<Page<UpgradeProposal>>(
      `/api/actions/upgrade-proposals${qs({ ...rest, status: status?.join(","), standalone: standalone ? "true" : undefined })}`,
    );
  },
  get(id: string) {
    return bffFetch<UpgradeProposal>(`/api/actions/upgrade-proposals/${id}`);
  },
  /** The upgrades Aegis recommends for every service — or one — with no CVE to look up. */
  recommendations(params: { serviceId?: string } = {}) {
    return bffFetch<UpgradeRecommendation[]>(`/api/actions/upgrade-recommendations${qs(params)}`);
  },
  /** Open the draft pull request of one drafted on its own. Aegis never merges it. */
  approve(id: string) {
    return bffFetch<UpgradeProposal>(`/api/actions/upgrade-proposals/${id}/approve`, { method: "POST" });
  },
  /** Dismiss with a reason: for a response's pull request, leave it out of the response. */
  dismiss(id: string, reason: string) {
    return bffFetch<UpgradeProposal>(`/api/actions/upgrade-proposals/${id}/dismiss`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  },
};

/**
 * Responses (E8 story 5): the risk ticket for a change's impact on one service, with the upgrade pull request
 * that fixes it where Aegis can automate one. Drafting sends nothing; approving files the ticket in the
 * organization's Jira, then opens the pull request under the ticket's key. An analyst, or the service's
 * owners, drafts and decides — never the drafter of a response with a pull request; a compliance admin audits.
 */
export const ticketApi = {
  list(
    params: {
      serviceId?: string;
      changeSignalId?: string;
      status?: RiskTicketStatus[];
      /** True: those that still need a person (the ticket or its pull request); false: those that don't. */
      pending?: boolean;
      page?: number;
      size?: number;
    } = {},
  ) {
    const { status, pending, ...rest } = params;
    return bffFetch<Page<RiskTicket>>(
      `/api/actions/risk-tickets${qs({ ...rest, status: status?.join(","), pending: pending === undefined ? undefined : String(pending) })}`,
    );
  },
  /** One ticket, with its ServiceNow incident fields. */
  get(id: string) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}`);
  },
  propose(input: { changeSignalId: string; serviceId: string }) {
    return bffFetch<RiskTicket>("/api/actions/risk-tickets", { method: "POST", body: JSON.stringify(input) });
  },
  /**
   * File the ticket in the organization's Jira, then open its pull request, if any, under the ticket's key.
   * `version` is the response as you read it: if it changed since, this is a 409 and nothing happens.
   */
  approve(id: string, version?: number) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/approve`, {
      method: "POST",
      body: JSON.stringify({ version }),
    });
  },
  /** Rewrite the drafted summary, risk, impact and actions before deciding; the first draft stays on record. */
  edit(id: string, input: { version: number; summary: string; risk: string; impact: string; actions: string[] }) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/edit`, {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  /** Replace Aegis's priority and due date (null: none) before deciding, with a reason; Aegis's stays on record. */
  override(id: string, input: { version: number; priority: TicketPriority; dueDate: string | null; reason: string }) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/override`, {
      method: "POST",
      body: JSON.stringify(input),
    });
  },
  /** Draft the upgrade pull request of a response that has none. */
  draftPullRequest(id: string) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/pull-requests`, { method: "POST" });
  },
  /** Open a filed response's pull request on its own: left for later, drafted afterwards, or failed. */
  approvePullRequest(id: string, proposalId: string) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/pull-requests/${proposalId}/approve`, {
      method: "POST",
    });
  },
  dismiss(id: string, reason: string, version?: number) {
    return bffFetch<RiskTicket>(`/api/actions/risk-tickets/${id}/dismiss`, {
      method: "POST",
      body: JSON.stringify({ reason, version }),
    });
  },
};

/**
 * Regulator memos (E8 story 6): a DORA notification about one change, drafted only when it materially
 * affects a service. An analyst drafts; a second analyst approves it, which makes it final. Aegis never
 * sends it.
 */
export const memoApi = {
  list(
    params: { changeSignalId?: string; status?: RegulatorMemoStatus[]; page?: number; size?: number } = {},
  ) {
    const { status, ...rest } = params;
    return bffFetch<Page<RegulatorMemo>>(
      `/api/actions/regulator-memos${qs({ ...rest, status: status?.join(",") })}`,
    );
  },
  get(id: string) {
    return bffFetch<RegulatorMemo>(`/api/actions/regulator-memos/${id}`);
  },
  propose(changeSignalId: string) {
    return bffFetch<RegulatorMemo>("/api/actions/regulator-memos", {
      method: "POST",
      body: JSON.stringify({ changeSignalId }),
    });
  },
  /** Make the memo final, ready for a person to submit. */
  approve(id: string) {
    return bffFetch<RegulatorMemo>(`/api/actions/regulator-memos/${id}/approve`, { method: "POST" });
  },
  dismiss(id: string, reason: string) {
    return bffFetch<RegulatorMemo>(`/api/actions/regulator-memos/${id}/dismiss`, {
      method: "POST",
      body: JSON.stringify({ reason }),
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

/**
 * The organization's integrations (aegis-core): which GitHub App installations it may act through, and the
 * Jira site its risk tickets are filed in. Everyone with a role can see them; only the organization admin
 * connects, configures or disconnects them.
 */
export const integrationsApi = {
  github() {
    return bffFetch<GitHubIntegration>("/api/integrations/github");
  },
  /** Where to send the organization admin to sign in to GitHub. */
  startGitHub() {
    return bffFetch<{ authorizeUrl: string }>("/api/integrations/github/authorizations", { method: "POST" });
  },
  /** Finish with what GitHub sent back: connects every installation the admin controls. */
  finishGitHub(code: string, state: string) {
    return bffFetch<GitHubConnectResult>("/api/integrations/github/connections", {
      method: "POST",
      body: JSON.stringify({ code, state }),
    });
  },
  disconnectGitHub(installationId: number) {
    return bffFetch<void>(`/api/integrations/github/connections/${installationId}`, { method: "DELETE" });
  },
  jira() {
    return bffFetch<JiraIntegration>("/api/integrations/jira");
  },
  /** Where to send the organization admin to grant Aegis a Jira site. */
  startJira() {
    return bffFetch<{ authorizeUrl: string }>("/api/integrations/jira/authorizations", { method: "POST" });
  },
  /** Finish with what Atlassian sent back; the project is chosen next. */
  finishJira(code: string, state: string) {
    return bffFetch<JiraIntegration>("/api/integrations/jira/connections", {
      method: "POST",
      body: JSON.stringify({ code, state }),
    });
  },
  /** The granted sites, a site's projects, and — once a project is picked — its issue types. */
  jiraOptions(params: { cloudId?: string; projectKey?: string } = {}) {
    return bffFetch<JiraOptions>(`/api/integrations/jira/options${qs(params)}`);
  },
  configureJira(settings: { cloudId: string; projectKey: string; issueType: string }) {
    return bffFetch<JiraIntegration>("/api/integrations/jira/settings", {
      method: "PUT",
      body: JSON.stringify(settings),
    });
  },
  disconnectJira() {
    return bffFetch<void>("/api/integrations/jira/connection", { method: "DELETE" });
  },
};
