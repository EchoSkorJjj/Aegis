import type { Page } from "@playwright/test";
import type { ChangeImpactResponse, FindingItem, OwnerRef, RiskTicket, Role, UpgradeProposal } from "../../src/lib/types";

export const OWNER: OwnerRef = { id: "owner-1", userId: "me", name: "Morgan Owner" };
const SERVICE = { id: "service-1", name: "Payments API", criticality: "CRITICAL" as const, exposure: "INTERNET_FACING" as const };
export const OTHER = { ...SERVICE, id: "service-2", name: "Batch worker" };
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
  decidedBy: null, decidedAt: null, dismissReason: null, issueKey: null, issueUrl: null, failureMessage: null, version: 0, pullRequests: [],
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
const ITEMS: FindingItem[] = Array.from({ length: 23 }, (_, index) => ({
  change: { ...CHANGE, id: `signal-${index + 1}`, title: index === 0 ? CHANGE.title : `Vulnerability ${index}`, signalType: index === 0 ? "VENDOR_OUTAGE" : "VULNERABILITY" },
  score: 90 - index, tier: "HIGH", services: [{ ...IMPACT.services[0], service: index % 2 === 0 ? SERVICE : OTHER,
    owners: index % 2 === 0 ? [OWNER] : [], score: 90 - index }],
}));

export async function fakeBff(page: Page, { role = "ANALYST", owned = 1, tickets = [TICKET], fail = false, responseFailure = false, vulnerability = false }: {
  role?: Role; owned?: number; tickets?: RiskTicket[]; fail?: boolean; responseFailure?: boolean; vulnerability?: boolean;
} = {}) {
  const listed: URLSearchParams[] = [];
  let failing = fail;
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3100" }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/auth/me") return route.fulfill({ json: {
      id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org", enabled: true, emailVerified: true, createdAt: "2026-10-01T00:00:00Z",
    } });
    if (url.pathname === "/api/orgs") return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    if (url.pathname === "/api/findings") {
      listed.push(url.searchParams);
      if (failing) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
      const filtered = ITEMS.filter((item) => item.services.some((service) =>
        (!url.searchParams.get("serviceId") || service.service.id === url.searchParams.get("serviceId")) &&
        (!url.searchParams.get("ownerId") || service.owners.some((owner) => owner.id === url.searchParams.get("ownerId"))) &&
        (!url.searchParams.get("mine") || owned > 0 && service.owners.some((owner) => owner.userId === "me"))));
      const number = Number(url.searchParams.get("page") || 0);
      return route.fulfill({ json: { services: [SERVICE, OTHER], owners: [OWNER], ownedServiceCount: owned,
        items: { content: filtered.slice(number * 20, (number + 1) * 20), totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / 20), number, size: 20 } } });
    }
    if (url.pathname === "/api/scoring/change-signals/signal-1") return route.fulfill({ json: {
      ...IMPACT, change: { ...CHANGE, signalType: vulnerability ? "VULNERABILITY" : "VENDOR_OUTAGE" },
    } });
    if (url.pathname === "/api/change-signals/signal-1") return route.fulfill({ json: {
      ...CHANGE, summary: "Card processing is experiencing elevated errors.", url: "https://status.example.test/incident-1",
      occurredAt: "2026-10-06T00:00:00Z", createdAt: null, updatedAt: null, severity: "major",
    } });
    if (url.pathname === "/api/actions/risk-tickets" && responseFailure) return route.fulfill({ status: 503, json: { message: "Unavailable" } });
    if (url.pathname === "/api/actions/risk-tickets") return route.fulfill({ json: {
      content: tickets, totalElements: tickets.length, totalPages: 1, number: 0, size: 100,
    } });
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
  return { listed, recover: () => { failing = false; } };
}

