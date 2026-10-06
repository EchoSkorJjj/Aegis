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

// ---- aegis-core inventory (the estate map; org-scoped — ADR §6) ------------------------------

/** Business-service criticality tier. Mirrors aegis-core BusinessCriticality. */
export type BusinessCriticality = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/** How reachable a service is from outside its own application. Mirrors aegis-core ServiceExposure. */
export type ServiceExposure = "ISOLATED" | "INTERNAL" | "INTERNET_FACING";

/**
 * How a service's SBOM was last imported — never the credential: an uploaded file (the API, e.g. CI), or its
 * linked repository read without credentials, through a host integration (the GitHub App), or with a
 * pasted access token. Mirrors aegis-core's SbomImportSource.
 */
export type SbomImportSource = "UPLOAD" | "PUBLIC" | "INTEGRATION" | "TOKEN";

export interface BusinessService {
  id: string;
  name: string;
  criticality: BusinessCriticality;
  exposure: ServiceExposure;
  repositoryUrl: string | null;
  repositoryBranch: string | null;
  /** When and how its SBOM was last imported; null until then, and again once the repository link changes. */
  sbomImportedAt: string | null;
  sbomImportSource: SbomImportSource | null;
  createdAt: string;
  updatedAt: string;
}

export interface BusinessServiceInput {
  name: string;
  criticality: BusinessCriticality;
  // Optional: a new service defaults to INTERNAL server-side, and on update an omitted exposure keeps
  // the current value — so a client that never sets it still works and never downgrades it.
  exposure?: ServiceExposure;
}

/** A software component, keyed by (ecosystem, name, version). */
export interface SoftwareComponent {
  id: string;
  ecosystem: string;
  name: string;
  version: string;
  createdAt: string;
  updatedAt: string;
}

export interface SoftwareComponentInput {
  ecosystem: string;
  name: string;
  version: string;
}

/** A third-party vendor the estate depends on. */
export interface ThirdPartyVendor {
  id: string;
  name: string;
  createdAt: string;
  updatedAt: string;
}

/** An owner (person/team) of a business service. */
/**
 * A service owner: an organization member holding SERVICE_OWNER (identity-service). `userId` is who
 * they are; `name`/`email` are the snapshot aegis-core took when they were last assigned.
 */
export interface Owner {
  id: string;
  userId: string;
  name: string;
  email: string | null;
  createdAt: string;
  updatedAt: string;
}

/** A member of the active organization, as identity-service's directory (`GET /users`) returns it. */
export interface DirectoryMember {
  id: string;
  email: string;
  displayName: string | null;
  roles: Role[];
  enabled: boolean;
}

/** A service using something, with the people accountable for it. */
export interface UsingService {
  service: BusinessService;
  owners: Owner[];
}

/** Every service linking a vendor (vendors are linked directly). Mirrors aegis-core VendorUsageResponse. */
export interface VendorUsage {
  vendor: ThirdPartyVendor;
  services: UsingService[];
}

/**
 * One service using one version of a package: directly, or `through` its own dependency graph — from the component
 * the service links down to the one that depends on this version, the shortest way.
 */
export interface PackageUse {
  service: BusinessService;
  owners: Owner[];
  component: SoftwareComponent;
  direct: boolean;
  through: SoftwareComponent[];
}

/** Every service using a package, any version of it, by service then version. Mirrors aegis-core PackageUsageResponse. */
export interface PackageUsage {
  ecosystem: string;
  name: string;
  uses: PackageUse[];
}

/** What a CycloneDX SBOM import changed on a service. Mirrors aegis-core SbomImportResult. */
export interface SbomImportResult {
  serviceId: string;
  componentsCreated: number;
  componentsMatched: number;
  /** Components dropped because they had no resolvable version. */
  componentsSkipped: number;
  dependenciesCreated: number;
  /** Dependency edges a re-import from the repository removed because the repository no longer declares them. */
  dependenciesRemoved: number;
  serviceComponentsLinked: number;
  /** Direct dependencies a re-import from the repository unlinked because the repository no longer declares them. */
  serviceComponentsUnlinked: number;
}

// ---- aegis-core change signals (unified advisories / vendor outages / security news) ---------

/** The kind of real-world change a signal represents. Mirrors aegis-core ChangeSignal.SignalType. */
export type SignalType = "VULNERABILITY" | "VENDOR_OUTAGE" | "SECURITY_NEWS";

/** Distinct values available for the advisory filter selects (GET /api/change-signals/facets). */
export interface ChangeSignalFacets {
  sources: string[];
  severities: string[];
  ecosystems: string[];
}

/** One row of the unified `change_signals` read model (global reference data — not org-scoped). */
export interface ChangeSignal {
  id: string;
  signalType: SignalType;
  sourceId: string;
  externalId: string;
  title: string | null;
  summary: string | null;
  severity: string | null;
  url: string | null;
  occurredAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

// ---- aegis-core matching (Epic 6): a change signal contextualized against the estate -----------

/** How much to trust a match. POSSIBLE = surfaced for review, never auto-actioned. */
export type MatchConfidence = "CONFIRMED" | "POSSIBLE";

/** Which relationship carried the change to a service. */
export type EvidenceKind = "ADVISORY_COMPONENT" | "VENDOR_DEPENDENCY";

/** How a component's name was tied to the advisory's product. */
export type IdentityMatch = "EXACT_PACKAGE" | "CPE_VENDOR_PRODUCT_ALIAS" | "AMBIGUOUS_NAME";

/** Whether the component's version could be placed in the advisory's affected range. */
export type VersionMatchState = "IN_RANGE" | "OUT_OF_RANGE" | "UNDETERMINED";

/** The change signal being matched, echoed back by the match API. */
export interface MatchedChangeRef {
  id: string;
  signalType: SignalType;
  sourceId: string;
  externalId: string;
  title: string | null;
}

export interface MatchedComponentRef {
  id: string;
  ecosystem: string;
  name: string;
  version: string;
}

export interface MatchedServiceRef {
  id: string;
  name: string;
  criticality: BusinessCriticality;
  exposure: ServiceExposure;
}

export interface MatchedRef {
  id: string;
  name: string;
}

/** An impacted service's owner: `id` is the inventory owner row, `userId` the person's identity id. */
export interface OwnerRef extends MatchedRef {
  userId: string;
}

/** The affected-product record (from the advisory's CPE) an advisory match came from. */
export interface AffectedProductEvidence {
  cpe: string | null;
  vendor: string | null;
  product: string | null;
  versionRange: string | null;
  identityMatch: IdentityMatch | null;
  versionMatch: VersionMatchState | null;
}

/** One complete reason a change reaches a service — the "why". */
export interface EvidenceChain {
  kind: EvidenceKind;
  confidence: MatchConfidence;
  explanation: string;
  /** Present for advisory matches. */
  affectedProduct: AffectedProductEvidence | null;
  /** Vulnerable component → … → the component the service uses directly (advisory matches). */
  componentPath: MatchedComponentRef[];
  /** Present for vendor-outage matches. */
  vendor: MatchedRef | null;
}

export interface ImpactedServiceMatch {
  service: MatchedServiceRef;
  confidence: MatchConfidence;
  owners: OwnerRef[];
  evidence: EvidenceChain[];
}

/** GET /api/matching/change-signals/{id} — a signal contextualized against the active org's estate. */
export interface ChangeMatchResponse {
  change: MatchedChangeRef;
  matchedComponentCount: number;
  matchedVendorCount: number;
  impactedServices: ImpactedServiceMatch[];
}

// ---- aegis-core scoring (Epic 7): how much a change matters to each service it reaches ---------

/** A score banded for display; the raw 0-100 score stays authoritative for ordering. */
export type ImpactTier = "NEGLIGIBLE" | "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

/** Where the severity multiplier came from: a published CVSS score, a feed's severity word, or neither. */
export type SeveritySource = "CVSS" | "SEVERITY_LABEL" | "NONE";

/** The terms of score = severity × exposure × criticality × confidence × 100, in that order. */
export type ScoreFactorKind = "SEVERITY" | "EXPOSURE" | "CRITICALITY" | "CONFIDENCE";

export interface SeverityInput {
  source: SeveritySource;
  cvssVersion: string | null;
  cvssBaseScore: number | null;
  label: string | null;
  factor: number;
}

/** Every multiplier in the score — enough to reproduce it by hand. */
export interface ScoreBreakdown {
  severityFactor: number;
  exposure: ServiceExposure | null;
  exposureFactor: number;
  /** False for a vendor outage: exposure is attack reach, and an outage is not an attack. */
  exposureApplies: boolean;
  criticality: BusinessCriticality | null;
  criticalityFactor: number;
  confidence: MatchConfidence | null;
  confidenceFactor: number;
}

/** One term of a score, explained (CS203-72). */
export interface ScoreFactor {
  factor: ScoreFactorKind;
  /** What the multiplier was read from, e.g. "CVSS 3.1 base 9.8", "INTERNET_FACING", "HIGH". */
  input: string;
  /** The multiplier, 0–1. */
  weight: number;
  /** Points the score would gain if this factor alone were at its maximum. */
  headroom: number;
  /** The factor holding the score down most (all tied ones; none at the worst case). */
  limiting: boolean;
  /** The aegis.scoring.* property that sets this weight; null for a published CVSS score. */
  setting: string | null;
}

export interface ScoredService {
  service: MatchedServiceRef;
  score: number;
  tier: ImpactTier;
  material: boolean;
  confidence: MatchConfidence;
  breakdown: ScoreBreakdown;
  factors: ScoreFactor[];
  explanation: string;
  owners: OwnerRef[];
  /** Matching's evidence chains for this service — why the change reaches it at all. */
  evidence: EvidenceChain[];
}

export interface ScoredOwner {
  owner: OwnerRef;
  score: number;
  tier: ImpactTier;
  impactedServiceCount: number;
  materialServiceCount: number;
  topService: MatchedServiceRef;
}

/** GET /api/scoring/change-signals/{id} — one signal scored per impacted service, highest first. */
export interface ChangeImpactResponse {
  change: MatchedChangeRef;
  /** From the one match the scores were computed from — no separate match request needed. */
  matchedComponentCount: number;
  matchedVendorCount: number;
  severity: SeverityInput;
  services: ScoredService[];
  owners: ScoredOwner[];
}

// ---- aegis-core responses (E8): drafted actions a person approves or dismisses ------------------

/**
 * Where a safe-upgrade proposal is. PROPOSED awaits a decision; OPENING is an approval in flight;
 * OPENED has a draft pull request; FAILED may be approved again or dismissed; DISMISSED is declined.
 */
export type UpgradeProposalStatus = "PROPOSED" | "OPENING" | "OPENED" | "FAILED" | "DISMISSED";

/** One package moved from one version to another in one manifest of the service's repository. */
export interface UpgradeEdit {
  componentId: string;
  ecosystem: string;
  packageName: string;
  fromVersion: string;
  toVersion: string;
  /** Repository-relative: client/package.json, pom.xml. */
  manifestPath: string;
}

/**
 * GET /api/actions/upgrade-proposals/{id} — one upgrade pull request as stored, plus its service's current
 * name. It edits every manifest that declares an affected package, and belongs to a response (the risk
 * ticket for the same vulnerability and service); `riskTicketId` is null only for one drafted on its own
 * before responses existed, which is still decided on its own.
 */
export interface UpgradeProposal {
  id: string;
  serviceId: string;
  /** The service's current name; null only if it was deleted mid-request (its proposals go with it). */
  serviceName: string | null;
  changeSignalId: string;
  vulnerabilityId: string;
  riskTicketId: string | null;
  /** In order; at least one. */
  edits: UpgradeEdit[];
  repositoryUrl: string;
  baseBranch: string | null;
  headBranch: string;
  matchConfidence: MatchConfidence;
  /** The exact pull-request title and (Markdown) body; a response's opens with its ticket's key in front. */
  title: string;
  body: string;
  status: UpgradeProposalStatus;
  createdBy: string;
  createdAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  dismissReason: string | null;
  pullRequestUrl: string | null;
  pullRequestNumber: string | null;
  failureMessage: string | null;
  version: number;
}

/**
 * Whether Aegis can draft an upgrade's pull request now: READY, or what stands in the way — the service's
 * repository isn't linked, the package comes in through another dependency, its ecosystem's manifest isn't
 * one Aegis edits, or no advisory publishes a version that fixes it.
 */
export type UpgradeReadiness = "READY" | "NO_REPOSITORY" | "TRANSITIVE" | "UNSUPPORTED" | "NO_FIX";

/** A vulnerability affecting a dependency, with the feeds' own severity. */
export interface VulnerabilityRef {
  /** Its canonical id — a CVE, or a GHSA with none. */
  id: string;
  changeSignalId: string | null;
  confidence: MatchConfidence;
  cvssVersion: string | null;
  cvssBaseScore: number | null;
  severity: string | null;
}

/**
 * GET /api/actions/upgrade-recommendations — a vulnerable dependency of a service and the upgrade that
 * fixes it, found by Aegis with no CVE to look up. Highest impact first.
 */
export interface UpgradeRecommendation {
  serviceId: string;
  serviceName: string;
  component: MatchedComponentRef;
  /** The direct dependency that pulls it in; null when the service uses it directly. */
  via: MatchedComponentRef | null;
  /** Every manifest declaring the direct dependency (client/package.json), as the import recorded them. */
  manifestPaths: string[];
  confidence: MatchConfidence;
  /** Most severe first. */
  vulnerabilities: VulnerabilityRef[];
  score: number;
  tier: ImpactTier;
  material: boolean;
  /** The lowest version that clears every advisory with a fix; null when none can be proven. */
  toVersion: string | null;
  /**
   * The vulnerability a response answers: the most severe one a fixed version clears, else the most severe;
   * null only when none has a change signal yet.
   */
  changeSignalId: string | null;
  readiness: UpgradeReadiness;
  /** Why it isn't READY. */
  reason: string | null;
  /** The current pull request that upgrades it, if any, and the response it belongs to (null: drafted on its own). */
  proposal: {
    id: string;
    status: UpgradeProposalStatus;
    toVersion: string;
    pullRequestUrl: string | null;
    riskTicketId: string | null;
  } | null;
  /** The response already drafted for it: the one upgrading it, else the one for `changeSignalId` here. */
  response: {
    ticketId: string;
    changeSignalId: string;
    status: RiskTicketStatus;
    issueKey: string | null;
    issueUrl: string | null;
  } | null;
  /** Whether you may draft a response: an analyst, or one of the service's owners. */
  canDraft: boolean;
}

/** A GitHub App installation the organization has connected (aegis-core V22): Aegis may act on its repositories. */
export interface GitHubConnection {
  installationId: number;
  accountLogin: string;
  accountId: number;
  accountType: "User" | "Organization";
  /** The organization admin who connected it (a user id). */
  connectedBy: string;
  connectedAt: string;
}

/** Whether GitHub is enabled on this deployment, where to install the App, and what this organization connected. */
export interface GitHubIntegration {
  enabled: boolean;
  installUrl: string | null;
  connections: GitHubConnection[];
}

/** What connecting did: the installations connected, and those seen but not the admin's to connect, with why. */
export interface GitHubConnectResult {
  connected: GitHubConnection[];
  skipped: { accountLogin: string; reason: string }[];
}

export type RiskTicketStatus = "PROPOSED" | "FILING" | "FILED" | "FAILED" | "DISMISSED";
export type TicketPriority = "HIGHEST" | "HIGH" | "MEDIUM" | "LOW" | "LOWEST";

/** One live material change, grouped by service and ranked within the selected estate scope. */
export interface FindingItem {
  change: MatchedChangeRef;
  score: number;
  tier: ImpactTier;
  services: Pick<ScoredService, "service" | "score" | "tier" | "confidence" | "owners" | "explanation">[];
}

export interface FindingQueueResponse {
  items: Page<FindingItem>;
  services: MatchedServiceRef[];
  owners: OwnerRef[];
  ownedServiceCount: number;
}

/**
 * GET /api/actions/risk-tickets/{id} — the response for one change in one service.
 * Approving files its ticket in Jira, then opens its upgrade pull request as a draft.
 */
export interface RiskTicket {
  id: string;
  serviceId: string;
  /** The service's current name; null only if it was deleted mid-request (its tickets go with it). */
  serviceName: string | null;
  changeSignalId: string;
  signalType: SignalType;
  changeTitle: string;
  title: string;
  priority: TicketPriority;
  /** yyyy-mm-dd, from the impact tier; null when the tier sets no deadline. */
  dueDate: string | null;
  score: number;
  tier: ImpactTier;
  labels: string[];
  summary: string;
  risk: string;
  impact: string;
  actions: string[];
  evidence: string[];
  aiDrafted: boolean;
  citations: string[];
  status: RiskTicketStatus;
  createdBy: string;
  createdAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  dismissReason: string | null;
  issueKey: string | null;
  issueUrl: string | null;
  failureMessage: string | null;
  version: number;
  /** Newest first; at most one isn't dismissed (the current one). Empty for a vendor outage, or nothing to automate. */
  pullRequests: UpgradeProposal[];
  /** One ticket only: the same ticket as ServiceNow incident fields, to paste or post. */
  serviceNow?: Record<string, string>;
}

export type MemoKind = "MAJOR_INCIDENT" | "CYBER_THREAT";
export type RegulatorMemoStatus = "PROPOSED" | "APPROVED" | "DISMISSED";

/** A service a memo's change affects, as Aegis scored it when the memo was drafted. */
export interface MemoAffectedService {
  serviceId: string;
  name: string;
  criticality: BusinessCriticality;
  exposure: ServiceExposure | null;
  score: number;
  tier: ImpactTier;
  material: boolean;
}

/**
 * GET /api/actions/regulator-memos/{id} — a drafted DORA notification (E8 story 6). `body` is the whole memo
 * in Markdown, rendered from its template. Approving makes it final; Aegis never sends it.
 */
export interface RegulatorMemo {
  id: string;
  changeSignalId: string;
  signalType: SignalType;
  changeTitle: string;
  kind: MemoKind;
  reference: string;
  /** When the organization became aware: an outage's start, or when the vulnerability was published. */
  detectedAt: string | null;
  services: MemoAffectedService[];
  summary: string;
  impact: string;
  measures: string;
  nextSteps: string;
  aiDrafted: boolean;
  citations: string[];
  body: string;
  status: RegulatorMemoStatus;
  createdBy: string;
  createdAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  dismissReason: string | null;
  version: number;
}

/** Whether Jira is enabled on this deployment and, when connected, where this organization's risk tickets go. */
export interface JiraIntegration {
  enabled: boolean;
  connected: boolean;
  siteName: string | null;
  siteUrl: string | null;
  /** Null until the organization admin chooses the project (and issue type) tickets are filed in. */
  projectKey: string | null;
  issueType: string | null;
  /** The organization admin who connected it (a user id). */
  connectedBy: string | null;
  connectedAt: string | null;
}

/** What an organization admin can choose from: the granted sites, a site's projects, and a project's issue types. */
export interface JiraOptions {
  sites: { cloudId: string; name: string; url: string }[];
  cloudId: string;
  projects: { key: string; name: string }[];
  issueTypes: string[];
}
