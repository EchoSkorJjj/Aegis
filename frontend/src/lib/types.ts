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

export interface BusinessService {
  id: string;
  name: string;
  criticality: BusinessCriticality;
  exposure: ServiceExposure;
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

/** A service impacted by a component — directly, or transitively through a dependency chain. */
export interface ComponentImpactedService {
  service: BusinessService;
  owners: Owner[];
  /** True only when the service uses the queried component itself, false when reached via a dependency. */
  direct: boolean;
  /** The component the service actually depends on directly (the queried one when `direct`). */
  viaComponent: SoftwareComponent;
}

export interface ComponentImpact {
  component: SoftwareComponent;
  services: ComponentImpactedService[];
}

/** A service impacted directly (e.g. through a vendor it uses). */
export interface ImpactedService {
  service: BusinessService;
  owners: Owner[];
}

export interface VendorImpact {
  vendor: ThirdPartyVendor;
  services: ImpactedService[];
}

/** What a CycloneDX SBOM import changed on a service. Mirrors aegis-core SbomImportResult. */
export interface SbomImportResult {
  serviceId: string;
  componentsCreated: number;
  componentsMatched: number;
  /** Components dropped because they had no resolvable version. */
  componentsSkipped: number;
  dependenciesCreated: number;
  serviceComponentsLinked: number;
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
  owners: MatchedRef[];
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
  owners: MatchedRef[];
}

export interface ScoredOwner {
  owner: MatchedRef;
  score: number;
  tier: ImpactTier;
  impactedServiceCount: number;
  materialServiceCount: number;
  topService: MatchedServiceRef;
}

/** GET /api/scoring/change-signals/{id} — one signal scored per impacted service, highest first. */
export interface ChangeImpactResponse {
  change: MatchedChangeRef;
  severity: SeverityInput;
  services: ScoredService[];
  owners: ScoredOwner[];
}
