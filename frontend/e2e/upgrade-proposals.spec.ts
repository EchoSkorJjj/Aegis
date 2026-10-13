import { expect, test, type Page } from "@playwright/test";
import type {
  ChangeImpactResponse,
  EvidenceChain,
  MatchedComponentRef,
  OwnerRef,
  Role,
  ScoreFactor,
  UpgradeEdit,
  UpgradeProposal,
} from "../src/lib/types";

// Browser contract checks use a fake BFF. Backend RBAC, tenancy and the proposal state machine are covered by
// UpgradeProposalIntegrationTest. A pull request is drafted and decided with its response (risk-tickets.spec);
// here: its own page, and those drafted on their own before responses, which are still decided on their own.
const SIGNAL = "signal-1";
const SERVICE = "service-1";
const LOG4J: MatchedComponentRef = {
  id: "log4j", ecosystem: "maven", name: "org.apache.logging.log4j:log4j-core", version: "2.14.1",
};

function chain(componentPath: MatchedComponentRef[]): EvidenceChain {
  return {
    kind: "ADVISORY_COMPONENT", confidence: "CONFIRMED", explanation: `${componentPath[0].name} is in range.`,
    affectedProduct: null, componentPath, vendor: null,
  };
}

function impact(owners: OwnerRef[]): ChangeImpactResponse {
  const factor = (kind: ScoreFactor["factor"], input: string): ScoreFactor => ({
    factor: kind, input, weight: 1, headroom: 0, limiting: false, setting: null,
  });
  return {
    change: { id: SIGNAL, signalType: "VULNERABILITY", sourceId: "nvd", externalId: "CVE-2021-44228", title: "Log4Shell" },
    matchedComponentCount: 1,
    matchedVendorCount: 0,
    severity: { source: "CVSS", cvssVersion: "3.1", cvssBaseScore: 10, label: null, factor: 1 },
    services: [{
      service: { id: SERVICE, name: "Payments API", criticality: "HIGH", exposure: "INTERNET_FACING" },
      score: 100, tier: "CRITICAL", material: true, confidence: "CONFIRMED",
      breakdown: {
        severityFactor: 1, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: true,
        criticality: "HIGH", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1,
      },
      factors: [factor("SEVERITY", "CVSS 3.1 base 10.0"), factor("EXPOSURE", "INTERNET_FACING"), factor("CRITICALITY", "HIGH")],
      explanation: "Worst case.",
      owners,
      evidence: [chain([LOG4J])],
    }],
    owners: [],
  };
}

function edit(manifestPath: string): UpgradeEdit {
  return { componentId: LOG4J.id, ecosystem: "maven", packageName: LOG4J.name, fromVersion: "2.14.1", toVersion: "2.17.1", manifestPath };
}

function proposal(overrides: Partial<UpgradeProposal> = {}): UpgradeProposal {
  return {
    id: "proposal-1", serviceId: SERVICE, serviceName: "Payments API", changeSignalId: SIGNAL,
    vulnerabilityId: "CVE-2021-44228", riskTicketId: null, edits: [edit("pom.xml")],
    repositoryUrl: "https://github.com/acme/payments", baseBranch: "main",
    headBranch: "aegis/upgrade-log4j-core-2.17.1-1a2b3c4d", matchConfidence: "CONFIRMED",
    title: "Upgrade org.apache.logging.log4j:log4j-core from 2.14.1 to 2.17.1 (CVE-2021-44228)",
    body: "## Safe upgrade proposed by Aegis\n\n<img src=x onerror=alert(1)> stays text",
    status: "PROPOSED", createdBy: "analyst-1", createdAt: "2026-09-24T10:00:00Z",
    decidedBy: null, decidedAt: null, dismissReason: null, pullRequestUrl: null, pullRequestNumber: null,
    failureMessage: null, version: 0,
    ...overrides,
  };
}

/** Dev-mode React runs effects twice, so compare the filters requested, not the number of requests. */
function distinct(values: string[]) {
  return [...new Set(values)];
}

type Options = { owners?: OwnerRef[]; proposals?: UpgradeProposal[]; approveFailure?: string };

async function fakeBff(page: Page, role: Role, { owners = [], proposals = [], approveFailure }: Options = {}) {
  const store = [...proposals];
  const writes: { path: string; body: unknown }[] = [];
  const listed: string[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org",
        enabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z",
      } });
    }
    if (path === "/api/actions/upgrade-recommendations") {
      return route.fulfill({ json: [] });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path === `/api/scoring/change-signals/${SIGNAL}`) {
      return route.fulfill({ json: impact(owners) });
    }
    if (path === `/api/inventory/services/${SERVICE}/owners`) {
      const content = owners.map((owner) => ({ ...owner, email: null, createdAt: "", updatedAt: "" }));
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 100 } });
    }
    if (path === "/api/users") {
      const content = [{ id: "analyst-1", email: "ana@example.test", displayName: "Ana Analyst", roles: ["ANALYST"], enabled: true }];
      return route.fulfill({ json: { content, totalElements: 1, totalPages: 1, number: 0, size: 100 } });
    }
    if (path === "/api/actions/upgrade-proposals" && request.method() === "GET") {
      listed.push(url.search);
      const status = url.searchParams.get("status");
      const standalone = url.searchParams.get("standalone") === "true";
      const content = store.filter((candidate) => (!status || status.split(",").includes(candidate.status))
        && (!standalone || candidate.riskTicketId === null));
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
    }
    const match = path.match(/^\/api\/actions\/upgrade-proposals\/([^/]+)(?:\/(approve|dismiss))?$/);
    if (match) {
      const index = store.findIndex((candidate) => candidate.id === match[1]);
      if (index < 0) return route.fulfill({ status: 404, json: { message: "Upgrade proposal not found" } });
      const decided = { decidedBy: "me", decidedAt: "2026-09-25T09:00:00Z" };
      if (match[2]) writes.push({ path, body: request.postDataJSON() });
      if (match[2] === "approve" && approveFailure) {
        store[index] = { ...store[index], ...decided, status: "FAILED", failureMessage: approveFailure };
        return route.fulfill({ status: 409, json: { message: approveFailure } });
      }
      if (match[2] === "approve") {
        store[index] = {
          ...store[index], ...decided, status: "OPENED",
          pullRequestUrl: "https://github.com/acme/payments/pull/42", pullRequestNumber: "42",
        };
      }
      if (match[2] === "dismiss") {
        store[index] = { ...store[index], ...decided, status: "DISMISSED", dismissReason: request.postDataJSON().reason };
      }
      return route.fulfill({ json: store[index] });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { writes, listed };
}

test("the impact page drafts a response for each service, not a pull request on its own", async ({ page }) => {
  await fakeBff(page, "ANALYST");
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByRole("heading", { name: "Log4Shell" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toHaveCount(0);
});

test("a response's pull request shows every edit, and is decided on its response", async ({ page }) => {
  await fakeBff(page, "ANALYST", {
    proposals: [proposal({
      riskTicketId: "ticket-1", edits: [edit("api/pom.xml"), edit("worker/pom.xml")],
      title: "Upgrade org.apache.logging.log4j:log4j-core to 2.17.1 in 2 manifests (CVE-2021-44228)",
    })],
  });
  await page.goto("/responses/upgrades/proposal-1");
  await expect(page.getByRole("heading", { name: `Upgrade ${LOG4J.name}` })).toBeVisible();
  const changes = page.getByRole("table", { name: "What the pull request changes" });
  await expect(changes.getByRole("row")).toHaveCount(1 + 2);
  await expect(changes).toContainText("api/pom.xml");
  await expect(changes).toContainText("worker/pom.xml");

  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("Part of a response")).toBeVisible();
  await expect(decision.getByRole("link", { name: "View the response →" })).toHaveAttribute("href", "/responses/tickets/ticket-1");
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("an override that only follows a bump is a note under the changes, not a change of its own", async ({ page }) => {
  const hono: UpgradeEdit = {
    componentId: "hono", ecosystem: "npm", packageName: "hono", fromVersion: "4.12.7", toVersion: "4.13.7",
    manifestPath: "packages/web/package.json", override: null,
  };
  await fakeBff(page, "ANALYST", {
    proposals: [proposal({
      riskTicketId: "ticket-1", title: "Upgrade hono from 4.12.7 to 4.13.7 (CVE-2026-39407)",
      edits: [hono, { ...hono, manifestPath: "pnpm-workspace.yaml", override: { via: null, packageManager: "pnpm", ifPresent: true } }],
    })],
  });
  await page.goto("/responses/upgrades/proposal-1");
  const changes = page.getByRole("table", { name: "What the pull request changes" });
  await expect(changes.getByRole("row")).toHaveCount(1 + 1);
  await expect(changes).toContainText("packages/web/package.json");
  await expect(changes).not.toContainText("pnpm-workspace.yaml");
  await expect(page.getByText("already overrides hono, the override is raised to the new version too", { exact: false }))
    .toContainText("Where pnpm-workspace.yaml already overrides hono");
});

test("one drafted on its own: approving opens a draft pull request, and the body is shown as text", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("Awaiting a decision")).toBeVisible();
  await expect(page.getByText("Drafted by Ana Analyst", { exact: false })).toBeVisible();
  await expect(page.locator("pre")).toContainText("<img src=x onerror=alert(1)> stays text");

  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Open draft PR", exact: true }).click();

  await expect(decision.getByText("Draft pull request opened")).toBeVisible();
  const pullRequest = decision.getByRole("link", { name: "Pull request #42" });
  await expect(pullRequest).toHaveAttribute("href", "https://github.com/acme/payments/pull/42");
  await expect(pullRequest).toHaveAttribute("target", "_blank");
  await expect(pullRequest).toHaveAttribute("rel", "noopener noreferrer");
  await expect(decision.getByText("Approved by you", { exact: false })).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/upgrade-proposals/proposal-1/approve", body: null }]);
});

test("one drafted on its own: a failed opening shows why, and can be dismissed with a reason", async ({ page }) => {
  const failure = "The manifest changed after it was read; approve again";
  const { writes } = await fakeBff(page, "ANALYST", { proposals: [proposal()], approveFailure: failure });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Open draft PR", exact: true }).click();

  await expect(decision.getByText("Opening the draft pull request failed")).toBeVisible();
  await expect(decision.getByText(failure)).toBeVisible();
  await expect(decision.getByRole("button", { name: "Try again", exact: true })).toBeVisible();

  await decision.getByRole("button", { name: "Dismiss", exact: true }).click();
  const confirm = page.getByRole("button", { name: "Dismiss proposal" });
  await expect(confirm).toBeDisabled();
  await page.getByLabel("Why is this upgrade not being made?").fill("Replacing the library next sprint.");
  await confirm.click();

  await expect(decision.getByText("Dismissed by you", { exact: false })).toBeVisible();
  await expect(decision.getByText("Replacing the library next sprint.")).toBeVisible();
  expect(writes.at(-1)).toEqual({
    path: "/api/actions/upgrade-proposals/proposal-1/dismiss", body: { reason: "Replacing the library next sprint." },
  });
});

test("one drafted on its own: a service owner who doesn't own the service can read it but not decide", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  // Shown only once ownership is known, so the missing buttons are the settled state.
  await expect(decision.getByText("An analyst or one of this service's owners approves or dismisses it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("one drafted on its own: an assigned service owner can decide", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { owners: [{ id: "owner-row", userId: "me", name: "Me" }], proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();
});

test("one drafted on its own: whoever drafted it may dismiss it, but someone else approves it", async ({ page }) => {
  await fakeBff(page, "ANALYST", { proposals: [proposal({ createdBy: "me" })] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(page.getByText("Drafted by you", { exact: false })).toBeVisible();
  await expect(decision.getByText("You drafted this, so someone else approves it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
});

test("one drafted on its own: a compliance admin audits it but doesn't decide", async ({ page }) => {
  await fakeBff(page, "COMPLIANCE_ADMIN", { proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You audit these decisions rather than make them.", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("the queues list pull requests drafted on their own, and only while there are any", async ({ page }) => {
  const dismissed = proposal({
    id: "proposal-2", status: "DISMISSED", dismissReason: "Not reachable in our usage",
    decidedBy: "analyst-1", decidedAt: "2026-09-24T12:00:00Z",
  });
  const inResponse = proposal({ id: "proposal-3", riskTicketId: "ticket-9" });
  const { listed } = await fakeBff(page, "ANALYST", { proposals: [proposal(), dismissed, inResponse] });

  await page.goto("/responses/review");
  const drafted = page.getByRole("region", { name: "Upgrade pull requests awaiting a decision" });
  await expect(drafted.getByRole("link", { name: `${LOG4J.name} 2.14.1 → 2.17.1` }))
    .toHaveAttribute("href", "/responses/upgrades/proposal-1");
  await expect(drafted.getByRole("row")).toHaveCount(1 + 1);

  await page.goto("/responses/history");
  const decided = page.getByRole("region", { name: "Upgrade pull requests" });
  await expect(decided.getByRole("link", { name: `${LOG4J.name} 2.14.1 → 2.17.1` }))
    .toHaveAttribute("href", "/responses/upgrades/proposal-2");
  await expect(decided.getByText("Not reachable in our usage")).toBeVisible();
  expect(distinct(listed).every((query) => query.includes("standalone=true"))).toBe(true);
});

test("with none drafted on their own, the queue shows no such section", async ({ page }) => {
  await fakeBff(page, "ANALYST", { proposals: [proposal({ riskTicketId: "ticket-9" })] });
  await page.goto("/responses/review");
  await expect(page.getByRole("region", { name: "Responses awaiting a decision" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Regulator memos awaiting a decision" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Upgrade pull requests awaiting a decision" })).toHaveCount(0);
});
