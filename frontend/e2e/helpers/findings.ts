import type { Page } from "@playwright/test";
import type {
  ChangeImpactResponse,
  ImpactTier,
  OwnedService,
  OwnerRef,
  RiskTicket,
  Role,
  TriageItem,
  UpgradeProposal,
  UpgradeRecommendation,
} from "../../src/lib/types";

// Browser contract checks use a fake BFF. How the queue is computed (recommendations, active outages, ranking,
// filters, who may draft) is covered by TriageApiIntegrationTest.
export const OWNER: OwnerRef = { id: "owner-1", userId: "me", name: "Morgan Owner" };
const SERVICE = { id: "service-1", name: "Payments API", criticality: "CRITICAL" as const, exposure: "INTERNET_FACING" as const };
export const OTHER = { ...SERVICE, id: "service-2", name: "Batch worker" };
export const LEDGER = { ...SERVICE, id: "service-3", name: "Ledger", criticality: "HIGH" as const, exposure: "INTERNAL" as const };
const CHANGE = { id: "signal-1", signalType: "VENDOR_OUTAGE" as const, sourceId: "vendor-status", externalId: "incident-1", title: "Card API outage" };
const IMPACT: ChangeImpactResponse = {
  change: CHANGE, matchedComponentCount: 0, matchedVendorCount: 1,
  severity: { source: "SEVERITY_LABEL", cvssVersion: null, cvssBaseScore: null, label: "major", factor: 0.75 },
  services: [{ service: SERVICE, score: 75, tier: "HIGH", material: true, confidence: "CONFIRMED", owners: [OWNER],
    breakdown: { severityFactor: 0.75, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: false,
      criticality: "CRITICAL", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1 },
    factors: [{ factor: "SEVERITY", input: "severity major", weight: 0.75, headroom: 25, limiting: true, setting: null }],
    explanation: "A major outage reaches Payments API.", evidence: [{ kind: "VENDOR_DEPENDENCY", confidence: "CONFIRMED",
      explanation: "Payments API depends on Acme Pay.", affectedProduct: null, componentPath: [], vendor: { id: "vendor-1", name: "Acme Pay" } }],
  }], owners: [],
};
export const TICKET: RiskTicket = {
  id: "ticket-1", serviceId: SERVICE.id, serviceName: SERVICE.name, changeSignalId: CHANGE.id, signalType: "VENDOR_OUTAGE",
  changeTitle: CHANGE.title, title: "Restore Payments API availability", priority: "HIGH", dueDate: "2026-10-07",
  score: 75, tier: "HIGH", labels: [], summary: "Vendor disruption affects payments.", risk: "Transactions may fail.",
  impact: "Customers cannot pay.", actions: ["Activate the fallback provider."], evidence: [], aiDrafted: true,
  citations: ["C1 Vendor status"], status: "PROPOSED", createdBy: "analyst-1", createdAt: "2026-10-06T00:00:00Z",
  decidedBy: null, decidedAt: null, dismissReason: null, issueKey: null, issueUrl: null, failureMessage: null,
  edit: null, override: null, approval: null, version: 0, stage: "IN_REVIEW", pullRequests: [],
};
export const PR: UpgradeProposal = {
  id: "pr-1", serviceId: SERVICE.id, serviceName: SERVICE.name, changeSignalId: CHANGE.id,
  vulnerabilityId: "CVE-2026-1234", riskTicketId: TICKET.id,
  edits: [{ componentId: "component-1", ecosystem: "npm", packageName: "axios", fromVersion: "0.21.1", toVersion: "1.15.0", manifestPath: "package.json" }],
  repositoryUrl: "https://github.com/acme/payments", baseBranch: null, headBranch: "aegis/upgrade",
  matchConfidence: "CONFIRMED", title: "Upgrade axios", body: "<script>externalText()</script>", status: "PROPOSED",
  createdBy: "analyst-1", createdAt: TICKET.createdAt, decidedBy: null, decidedAt: null, dismissReason: null,
  pullRequestUrl: null, pullRequestNumber: null, failureMessage: null, version: 0,
};

function tierOf(score: number): ImpactTier {
  return score >= 90 ? "CRITICAL" : score >= 70 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW";
}

/** The index-th item of the queue: a vulnerable dependency of Payments API (even) or Batch worker (odd). */
function vulnerability(
  index: number,
  overrides: Partial<Omit<UpgradeRecommendation, "canDraft">> = {},
  stage?: TriageItem["stage"],
): TriageItem {
  const service = index % 2 === 0 ? SERVICE : OTHER;
  const score = 90 - index;
  const upgrade: Omit<UpgradeRecommendation, "canDraft"> = {
    serviceId: service.id, serviceName: service.name,
    component: { id: `lib-${index}`, ecosystem: index % 3 === 0 ? "maven" : "npm", name: `lib-${String(index).padStart(2, "0")}`, version: "1.0.0" },
    via: null, manifestPaths: ["package.json"], confidence: "CONFIRMED",
    vulnerabilities: [{ id: `CVE-2026-${1000 + index}`, changeSignalId: `signal-${index + 1}`, confidence: "CONFIRMED",
      cvssVersion: "3.1", cvssBaseScore: 9.8, severity: "CRITICAL" }],
    score, tier: tierOf(score), material: true, toVersion: "1.0.1", changeSignalId: `signal-${index + 1}`,
    readiness: "READY", reason: null, overrideIn: [], proposal: null, response: null,
    usage: { status: "UNKNOWN", reason: "The service's code hasn't been read yet: import the SBOM from its repository", importedAs: [], siteCount: 0, sites: [] }, viaUsage: null,
    ...overrides,
  };
  return {
    kind: "VULNERABILITY", changeSignalId: upgrade.changeSignalId, service, owners: service === SERVICE ? [OWNER] : [],
    score: upgrade.score, tier: upgrade.tier, confidence: upgrade.confidence, upgrade, outage: null,
    response: upgrade.response, stage: stage ?? upgrade.response?.stage ?? "NEW", canDraft: false,
  };
}

/** 23 items, highest impact first: an outage with a drafted response, then vulnerable dependencies. */
const ITEMS: TriageItem[] = [
  {
    kind: "VENDOR_OUTAGE", changeSignalId: CHANGE.id, service: SERVICE, owners: [OWNER], score: 90, tier: "CRITICAL",
    confidence: "CONFIRMED", upgrade: null,
    outage: { id: CHANGE.id, title: CHANGE.title, vendorName: "Acme Pay", status: "investigating", impact: "major",
      startedAt: "2026-10-06T00:00:00Z", lastUpdateAt: "2026-10-06T01:00:00Z", url: "https://status.example.test/incident-1" },
    response: { ticketId: TICKET.id, changeSignalId: CHANGE.id, status: "PROPOSED", stage: "IN_REVIEW", issueKey: null, issueUrl: null },
    stage: "IN_REVIEW", canDraft: false,
  },
  vulnerability(1, {
    component: { id: "axios-0211", ecosystem: "npm", name: "axios", version: "0.21.1" }, toVersion: "1.15.0",
    changeSignalId: "signal-axios", manifestPaths: ["client/package.json", "server/package.json"],
    vulnerabilities: [
      { id: "CVE-2026-40175", changeSignalId: "signal-axios", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 7.5, severity: "HIGH" },
      { id: "CVE-2023-45857", changeSignalId: "signal-other", confidence: "CONFIRMED", cvssVersion: "3.1", cvssBaseScore: 6.5, severity: "MEDIUM" },
    ],
  }),
  vulnerability(2, {
    component: { id: "minimist-120", ecosystem: "npm", name: "minimist", version: "1.2.0" }, toVersion: "1.2.6",
    readiness: "NO_REPOSITORY", reason: "Link the service's repository so Aegis can draft the pull request",
  }),
  // lib-04's response was dismissed: its item is closed, and a new response can still be drafted.
  ...Array.from({ length: 20 }, (_, index) => vulnerability(index + 3, {}, index + 3 === 4 ? "CLOSED" : undefined)),
];

/** "My services": Payments API, with the items above; Ledger, with nothing yet and no SBOM or repository. */
const OWNED: OwnedService[] = [
  { service: SERVICE, toRespond: 12, noResponseYet: 11, topScore: 90, topTier: "CRITICAL", awaitingDecision: 1,
    repositoryUrl: "https://github.com/acme/payments", sbomImportedAt: "2026-10-05T00:00:00Z", sbomImportSource: "PUBLIC" },
  { service: LEDGER, toRespond: 0, noResponseYet: 0, topScore: null, topTier: null, awaitingDecision: 0,
    repositoryUrl: null, sbomImportedAt: null, sbomImportSource: null },
];

/** Whether the signed-in user ("me") may draft: an analyst, or an owner of the item's service. */
function mayDraft(role: Role, item: TriageItem): boolean {
  return role === "ANALYST" || (role === "SERVICE_OWNER" && item.owners.some((owner) => owner.userId === "me"));
}

export async function fakeBff(page: Page, { role = "ANALYST", owned = 1, tickets = [TICKET], fail = false, responseFailure = false, vulnerability = false }: {
  role?: Role; owned?: number; tickets?: RiskTicket[]; fail?: boolean; responseFailure?: boolean; vulnerability?: boolean;
} = {}) {
  const listed: URLSearchParams[] = [];
  const overviews: string[] = [];
  const drafts: unknown[] = [];
  let failing = fail;
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3100" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const query = url.searchParams;
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org", enabled: true, emailVerified: true, createdAt: "2026-10-01T00:00:00Z",
    } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    if (url.pathname === "/api/findings/my-services") {
      overviews.push(url.pathname);
      if (failing) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
      return route.fulfill({ json: OWNED.slice(0, owned) });
    }
    if (url.pathname === "/api/findings") {
      listed.push(query);
      if (failing) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
      const scope = ITEMS.filter((item) => !query.get("mine") || (owned > 0 && item.owners.some((owner) => owner.userId === "me")));
      const selected = scope.filter((item) =>
        (!query.get("serviceId") || item.service.id === query.get("serviceId")) &&
        (!query.get("ownerId") || item.owners.some((owner) => owner.id === query.get("ownerId"))));
      const filtered = selected.filter((item) =>
        (!query.get("ecosystem") || item.upgrade?.component.ecosystem === query.get("ecosystem")) &&
        (!query.get("tier") || item.tier === query.get("tier")))
        .map((item) => ({ ...item, canDraft: mayDraft(role, item) }));
      const number = Number(query.get("page") || 0);
      const size = Number(query.get("size") || 20);
      const services = [...new Map(scope.map((item) => [item.service.id, item.service])).values()];
      const owners = [...new Map(scope.flatMap((item) => item.owners).map((owner) => [owner.id, owner])).values()];
      const ecosystems = [...new Set(selected.flatMap((item) => (item.upgrade ? [item.upgrade.component.ecosystem] : [])))].sort();
      return route.fulfill({ json: { services, owners, ecosystems,
        items: { content: filtered.slice(number * size, (number + 1) * size), totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / size), number, size,
          first: number === 0, last: (number + 1) * size >= filtered.length } } });
    }
    if (url.pathname === "/api/scoring/change-signals/signal-1") return route.fulfill({ json: {
      ...IMPACT, change: { ...CHANGE, signalType: vulnerability ? "VULNERABILITY" : "VENDOR_OUTAGE" },
    } });
    if (url.pathname === "/api/change-signals/signal-1") return route.fulfill({ json: {
      ...CHANGE, summary: "Card processing is experiencing elevated errors.", url: "https://status.example.test/incident-1",
      occurredAt: "2026-10-06T00:00:00Z", createdAt: null, updatedAt: null, severity: "major",
    } });
    if (url.pathname === "/api/actions/risk-tickets" && request.method() === "POST") {
      drafts.push(request.postDataJSON());
      return route.fulfill({ status: 201, json: { ...TICKET, id: "ticket-new" } });
    }
    if (url.pathname.startsWith("/api/actions/risk-tickets/")) return route.fulfill({ status: 404, json: { message: "Risk ticket not found" } });
    if (url.pathname === "/api/actions/risk-tickets" && responseFailure) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
    if (url.pathname === "/api/actions/risk-tickets") return route.fulfill({ json: {
      content: tickets, totalElements: tickets.length, totalPages: 1, number: 0, size: 100,
    } });
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
  return { listed, overviews, drafts, recover: () => { failing = false; } };
}
