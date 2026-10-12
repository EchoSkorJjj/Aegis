import { expect, test, type Page } from "@playwright/test";
import type {
  ChangeImpactResponse,
  JiraIntegration,
  OwnerRef,
  RiskTicket,
  Role,
  ScoreFactor,
  UpgradeProposal,
} from "../src/lib/types";

// Browser contract checks use a fake BFF. Backend RBAC, tenancy, the response state machine and filing in
// Jira are covered by RiskTicketIntegrationTest, UpgradeProposalIntegrationTest and the Jira adapter tests.
const SIGNAL = "signal-1";
const SERVICE = "service-1";
const JIRA: JiraIntegration = {
  enabled: true, connected: true, siteName: "Acme", siteUrl: "https://acme.atlassian.net", projectKey: "SEC",
  issueType: "Task", connectedBy: "admin-1", connectedAt: "2026-09-26T01:00:00Z",
};

function impact(owners: OwnerRef[]): ChangeImpactResponse {
  const factor = (kind: ScoreFactor["factor"], input: string): ScoreFactor => ({
    factor: kind, input, weight: 1, headroom: 0, limiting: false, setting: null,
  });
  return {
    change: { id: SIGNAL, signalType: "VENDOR_OUTAGE", sourceId: "vendor-status", externalId: "acme:incident-1", title: "Card API outage" },
    matchedComponentCount: 0,
    matchedVendorCount: 1,
    severity: { source: "SEVERITY_LABEL", cvssVersion: null, cvssBaseScore: null, label: "major", factor: 0.75 },
    services: [{
      service: { id: SERVICE, name: "Payments API", criticality: "CRITICAL", exposure: "INTERNET_FACING" },
      score: 75, tier: "HIGH", material: true, confidence: "CONFIRMED",
      breakdown: {
        severityFactor: 0.75, exposure: "INTERNET_FACING", exposureFactor: 1, exposureApplies: false,
        criticality: "CRITICAL", criticalityFactor: 1, confidence: "CONFIRMED", confidenceFactor: 1,
      },
      factors: [factor("SEVERITY", "severity major"), factor("EXPOSURE", "INTERNET_FACING"), factor("CRITICALITY", "CRITICAL")],
      explanation: "A major outage at a vendor a critical service depends on.",
      owners,
      evidence: [{
        kind: "VENDOR_DEPENDENCY", confidence: "CONFIRMED", explanation: "Payments API depends on Acme Pay.",
        affectedProduct: null, componentPath: [], vendor: { id: "vendor-1", name: "Acme Pay" },
      }],
    }],
    owners: [],
  };
}

function pullRequest(overrides: Partial<UpgradeProposal> = {}): UpgradeProposal {
  return {
    id: "pr-1", serviceId: SERVICE, serviceName: "Payments API", changeSignalId: SIGNAL,
    vulnerabilityId: "CVE-2021-44228", riskTicketId: "ticket-1",
    edits: [
      { componentId: "log4j", ecosystem: "maven", packageName: "org.apache.logging.log4j:log4j-core", fromVersion: "2.14.1", toVersion: "2.17.1", manifestPath: "api/pom.xml" },
      { componentId: "log4j", ecosystem: "maven", packageName: "org.apache.logging.log4j:log4j-core", fromVersion: "2.14.1", toVersion: "2.17.1", manifestPath: "worker/pom.xml" },
    ],
    repositoryUrl: "https://github.com/acme/payments", baseBranch: null, headBranch: "aegis/upgrade-log4j-core-2.17.1-1a2b3c4d",
    matchConfidence: "CONFIRMED", title: "Upgrade org.apache.logging.log4j:log4j-core to 2.17.1 in 2 manifests (CVE-2021-44228)",
    body: "## Safe upgrade proposed by Aegis", status: "PROPOSED", createdBy: "analyst-1", createdAt: "2026-09-26T02:00:00Z",
    decidedBy: null, decidedAt: null, dismissReason: null, pullRequestUrl: null, pullRequestNumber: null,
    failureMessage: null, version: 0,
    ...overrides,
  };
}

/** A vulnerability's response, with its pull request; `outage()` is one with none. */
function ticket(overrides: Partial<RiskTicket> = {}): RiskTicket {
  return {
    id: "ticket-1", serviceId: SERVICE, serviceName: "Payments API", changeSignalId: SIGNAL,
    signalType: "VULNERABILITY", changeTitle: "CVE-2021-44228",
    title: "CVE-2021-44228 affects Payments API", priority: "HIGHEST", dueDate: "2026-10-03",
    score: 91, tier: "CRITICAL", labels: ["aegis", "vulnerability", "impact-critical", "CVE-2021-44228"],
    summary: "Payments API runs a vulnerable log4j-core.", risk: "Remote code execution.",
    impact: "Takeover of the service.", actions: ["Upgrade log4j-core to 2.17.1.", "Check the other services."],
    evidence: ["log4j-core 2.14.1 is in the affected range.", "Impact score 91.0 (CRITICAL)"], aiDrafted: true,
    citations: ["C1 NVD description"], status: "PROPOSED", createdBy: "analyst-1", createdAt: "2026-09-26T02:00:00Z",
    decidedBy: null, decidedAt: null, dismissReason: null, issueKey: null, issueUrl: null, failureMessage: null,
    edit: null, override: null, approval: null, version: 0, stage: "IN_REVIEW", pullRequests: [pullRequest()],
    serviceNow: {
      short_description: "CVE-2021-44228 affects Payments API", description: "Summary",
      impact: "1", urgency: "1", cmdb_ci: "Payments API", due_date: "2026-10-03 00:00:00",
    },
    ...overrides,
  };
}

function outage(overrides: Partial<RiskTicket> = {}): RiskTicket {
  return ticket({
    id: "ticket-2", signalType: "VENDOR_OUTAGE", changeTitle: "Card API outage",
    title: "Vendor outage affects Payments API: Card API outage", priority: "HIGH", tier: "HIGH", score: 75,
    labels: ["aegis", "vendor-outage", "impact-high"], pullRequests: [], ...overrides,
  });
}

type Options = {
  owners?: OwnerRef[];
  tickets?: RiskTicket[];
  jira?: JiraIntegration;
  /** The pull request fails to open when the response is approved. */
  pullRequestFailure?: string;
  /** Someone else changes the response just before the first decision on it reaches Aegis. */
  changedMeanwhile?: Partial<RiskTicket>;
};

async function fakeBff(
  page: Page,
  role: Role,
  { owners = [], tickets = [], jira = JIRA, pullRequestFailure, changedMeanwhile }: Options = {},
) {
  let meanwhile = changedMeanwhile;
  const store = tickets.map((candidate) => ({ ...candidate, pullRequests: [...candidate.pullRequests] }));
  const writes: { path: string; body: unknown }[] = [];
  const listed: string[] = [];
  const empty = { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 };
  const decided = { decidedBy: "me", decidedAt: "2026-09-26T09:00:00Z" };
  const find = (id: string) => store.findIndex((candidate) => candidate.id === id);
  const open = (pr: UpgradeProposal, key: string): UpgradeProposal => ({
    ...pr, ...decided, status: "OPENED", title: `${key}: ${pr.title}`, failureMessage: null,
    pullRequestUrl: "https://github.com/acme/payments/pull/42", pullRequestNumber: "42",
  });
  const pending = (candidate: RiskTicket) => ["PROPOSED", "FILING", "FAILED"].includes(candidate.status)
    || candidate.pullRequests.some((pr) => ["PROPOSED", "OPENING", "FAILED"].includes(pr.status));
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
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path === "/api/actions/upgrade-recommendations") {
      return route.fulfill({ json: [] });
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
    if (path === "/api/integrations/jira") {
      return route.fulfill({ json: jira });
    }
    if (path === "/api/actions/risk-tickets" && request.method() === "GET") {
      listed.push(url.search);
      const status = url.searchParams.get("status");
      const signal = url.searchParams.get("changeSignalId");
      const wanted = url.searchParams.get("pending");
      const content = store.filter((candidate) => (!status || status.split(",").includes(candidate.status))
        && (!signal || candidate.changeSignalId === signal)
        && (wanted === null || pending(candidate) === (wanted === "true")))
        .map((listedTicket) => ({ ...listedTicket, serviceNow: undefined }));
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
    }
    if (path === "/api/actions/risk-tickets" && request.method() === "POST") {
      writes.push({ path, body: request.postDataJSON() });
      const created = outage({ id: "ticket-new", createdBy: "me" });
      store.push(created);
      return route.fulfill({ status: 201, json: created });
    }
    const pr = path.match(/^\/api\/actions\/upgrade-proposals\/([^/]+)\/dismiss$/);
    if (pr) {
      writes.push({ path, body: request.postDataJSON() });
      for (const candidate of store) {
        candidate.pullRequests = candidate.pullRequests.map((existing) => existing.id === pr[1]
          ? { ...existing, ...decided, status: "DISMISSED", dismissReason: request.postDataJSON().reason } : existing);
      }
      return route.fulfill({ json: {} });
    }
    const draftPr = path.match(/^\/api\/actions\/risk-tickets\/([^/]+)\/pull-requests$/);
    if (draftPr) {
      writes.push({ path, body: null });
      const index = find(draftPr[1]);
      store[index] = { ...store[index], pullRequests: [pullRequest({ id: "pr-new", createdBy: "me" }), ...store[index].pullRequests] };
      return route.fulfill({ status: 201, json: store[index] });
    }
    const approvePr = path.match(/^\/api\/actions\/risk-tickets\/([^/]+)\/pull-requests\/([^/]+)\/approve$/);
    if (approvePr) {
      writes.push({ path, body: null });
      const index = find(approvePr[1]);
      store[index] = { ...store[index], pullRequests: store[index].pullRequests.map((existing) =>
        existing.id === approvePr[2] ? open(existing, store[index].issueKey ?? "SEC-7") : existing) };
      return route.fulfill({ json: store[index] });
    }
    const match = path.match(/^\/api\/actions\/risk-tickets\/([^/]+)(?:\/(approve|dismiss|edit|override|override\/withdraw))?$/);
    if (match) {
      const index = find(match[1]);
      if (index < 0) return route.fulfill({ status: 404, json: { message: "Risk ticket not found" } });
      if (match[2]) writes.push({ path, body: request.postDataJSON() });
      if (match[2] && meanwhile) {
        store[index] = { ...store[index], ...meanwhile, version: store[index].version + 1 };
        meanwhile = undefined;
      }
      // As aegis-core: a decision on a version that is no longer the response's is a conflict.
      const seen = match[2] ? request.postDataJSON()?.version : undefined;
      if (seen !== undefined && seen !== store[index].version) {
        return route.fulfill({ status: 409, json: {
          message: "This response changed after you opened it: someone else edited or decided it. Reload it and decide again",
        } });
      }
      if (match[2] === "edit") {
        const body = request.postDataJSON();
        const current = store[index];
        store[index] = { ...current, summary: body.summary, risk: body.risk, impact: body.impact, actions: body.actions,
          version: current.version + 1,
          edit: { draftedSummary: current.edit?.draftedSummary ?? current.summary,
            draftedRisk: current.edit?.draftedRisk ?? current.risk, draftedImpact: current.edit?.draftedImpact ?? current.impact,
            draftedActions: current.edit?.draftedActions ?? current.actions, by: "me", at: "2026-09-26T08:00:00Z" } };
      }
      if (match[2] === "override") {
        const body = request.postDataJSON();
        const current = store[index];
        store[index] = { ...current, priority: body.priority, dueDate: body.dueDate, version: current.version + 1,
          override: { assessedPriority: current.override?.assessedPriority ?? current.priority,
            assessedDueDate: current.override ? current.override.assessedDueDate : current.dueDate,
            reason: body.reason, by: "me", at: "2026-09-26T08:30:00Z" } };
      }
      if (match[2] === "override/withdraw") {
        const current = store[index];
        store[index] = { ...current, priority: current.override?.assessedPriority ?? current.priority,
          dueDate: current.override ? current.override.assessedDueDate : current.dueDate, override: null,
          version: current.version + 1 };
      }
      if (match[2] === "approve") {
        const filed = { ...store[index], ...decided, status: "FILED" as const, stage: "ACTIONED" as const,
          approval: { by: "me", at: decided.decidedAt },
          issueKey: "SEC-7", issueUrl: "https://acme.atlassian.net/browse/SEC-7", version: store[index].version + 2 };
        filed.pullRequests = filed.pullRequests.map((existing) =>
          existing.status !== "PROPOSED" && existing.status !== "FAILED" ? existing
            : pullRequestFailure ? { ...existing, ...decided, status: "FAILED", failureMessage: pullRequestFailure }
              : open(existing, "SEC-7"));
        store[index] = filed;
      }
      if (match[2] === "dismiss") {
        const reason = request.postDataJSON().reason;
        store[index] = { ...store[index], ...decided, status: "DISMISSED", stage: "CLOSED", dismissReason: reason,
          version: store[index].version + 1,
          pullRequests: store[index].pullRequests.map((existing) => existing.status === "PROPOSED"
            ? { ...existing, ...decided, status: "DISMISSED", dismissReason: reason } : existing) };
      }
      return route.fulfill({ json: store[index] });
    }
    return route.fulfill({ json: empty });
  });
  return { writes, listed };
}

/** Dev-mode React runs effects twice, so compare the filters requested, not the number of requests. */
function distinct(values: string[]) {
  return [...new Set(values)];
}

test("an analyst drafts a response from the impact page and lands on it", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST");
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByRole("heading", { name: "Card API outage" })).toBeVisible();

  await page.getByRole("button", { name: "Draft response" }).click();
  await expect(page).toHaveURL(/\/responses\/tickets\/ticket-new$/);
  await expect(page.getByRole("heading", { name: "Vendor outage affects Payments API: Card API outage" })).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/risk-tickets", body: { changeSignalId: SIGNAL, serviceId: SERVICE } }]);
});

test("a response already drafted is linked, not offered again", async ({ page }) => {
  await fakeBff(page, "ANALYST", {
    tickets: [outage({ id: "ticket-1", status: "FILED", issueKey: "SEC-7", issueUrl: "https://acme.atlassian.net/browse/SEC-7" })],
  });
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByRole("link", { name: "View response →" })).toHaveAttribute("href", "/responses/tickets/ticket-1");
  await expect(page.getByText("SEC-7")).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response" })).toHaveCount(0);
});

test("approving a response files its ticket, then opens its pull request under the ticket's key", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { tickets: [ticket()] });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("Awaiting a decision")).toBeVisible();
  await expect(decision.getByText("Approving files this ticket in SEC on Acme as a Task, then opens the upgrade pull request below",
    { exact: false })).toBeVisible();
  await expect(page.getByText("Drafted by Ana Analyst", { exact: false })).toBeVisible();

  const upgrade = page.getByRole("region", { name: "Upgrade pull request" });
  await expect(upgrade.getByRole("table", { name: "What the pull request changes" }).getByRole("row")).toHaveCount(1 + 2);
  await expect(upgrade.getByText("Opens as a draft, with the ticket's key in front of its title", { exact: false })).toBeVisible();
  await expect(upgrade.getByRole("link", { name: "The pull request's full text →" })).toHaveAttribute("href", "/responses/upgrades/pr-1");

  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Approve response", exact: true }).click();

  await expect(decision.getByText("Filed in Jira")).toBeVisible();
  const issue = decision.getByRole("link", { name: "SEC-7" });
  await expect(issue).toHaveAttribute("href", "https://acme.atlassian.net/browse/SEC-7");
  await expect(issue).toHaveAttribute("rel", "noopener noreferrer");
  await expect(upgrade.getByText("Draft PR open")).toBeVisible();
  await expect(upgrade.getByText("SEC-7: Upgrade org.apache.logging.log4j:log4j-core", { exact: false })).toBeVisible();
  await expect(upgrade.getByRole("link", { name: "Pull request #42" }))
    .toHaveAttribute("href", "https://github.com/acme/payments/pull/42");
  // The approval names the version the approver read, so a response changed meanwhile isn't approved blind.
  expect(writes).toEqual([{ path: "/api/actions/risk-tickets/ticket-1/approve", body: { version: 0 } }]);
});

test("whoever drafted a response can't approve its pull request, but can leave it out and file the ticket", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [ticket({ createdBy: "me", pullRequests: [pullRequest({ createdBy: "me" })] })],
  });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You drafted this response, so someone else approves it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);

  const upgrade = page.getByRole("region", { name: "Upgrade pull request" });
  await upgrade.getByRole("button", { name: "Leave out", exact: true }).click();
  await page.getByLabel("Why leave the pull request out?").fill("We upgrade by hand with the next release.");
  await page.getByRole("button", { name: "Leave it out" }).click();

  await expect(upgrade.getByText("Left out by you", { exact: false })).toBeVisible();
  await decision.getByRole("button", { name: "File ticket", exact: true }).click();
  await page.getByRole("button", { name: "File ticket", exact: true }).last().click();
  await expect(decision.getByText("Filed in Jira")).toBeVisible();
  expect(writes).toEqual([
    { path: "/api/actions/upgrade-proposals/pr-1/dismiss", body: { reason: "We upgrade by hand with the next release." } },
    { path: "/api/actions/risk-tickets/ticket-1/approve", body: { version: 0 } },
  ]);
});

test("a response without a pull request is filed by its drafter", async ({ page }) => {
  await fakeBff(page, "ANALYST", { tickets: [outage({ createdBy: "me" })] });
  await page.goto("/responses/tickets/ticket-2");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByRole("button", { name: "File ticket", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Upgrade pull request" })).toHaveCount(0);
});

test("a pull request that fails to open after the ticket is filed is tried again on its own", async ({ page }) => {
  const failure = "The manifest changed after it was read; approve again";
  const { writes } = await fakeBff(page, "ANALYST", { tickets: [ticket()], pullRequestFailure: failure });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Approve response", exact: true }).click();

  await expect(decision.getByText("Filed in Jira")).toBeVisible();
  const upgrade = page.getByRole("region", { name: "Upgrade pull request" });
  await expect(upgrade.getByText("Opening failed")).toBeVisible();
  await expect(upgrade.getByText(failure)).toBeVisible();

  await upgrade.getByRole("button", { name: "Try again", exact: true }).click();
  await page.getByRole("button", { name: "Open draft PR", exact: true }).click();
  await expect(upgrade.getByText("Draft PR open")).toBeVisible();
  expect(writes.at(-1)).toEqual({ path: "/api/actions/risk-tickets/ticket-1/pull-requests/pr-1/approve", body: null });
});

test("a response whose pull request was left out can draft one again", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [ticket({
      status: "FILED", issueKey: "SEC-7", issueUrl: "https://acme.atlassian.net/browse/SEC-7", decidedBy: "analyst-1",
      pullRequests: [pullRequest({ status: "DISMISSED", dismissReason: "Upgrading with the next release", decidedBy: "analyst-1" })],
    })],
  });
  await page.goto("/responses/tickets/ticket-1");
  const upgrade = page.getByRole("region", { name: "Upgrade pull request" });
  await expect(upgrade.getByText("Left out by Ana Analyst", { exact: false })).toContainText("Upgrading with the next release");

  await upgrade.getByRole("button", { name: "Draft upgrade pull request" }).click();
  await expect(upgrade.getByText("Awaiting decision")).toBeVisible();
  // Drafted by you, so someone else opens it.
  await expect(upgrade.getByText("You drafted this pull request, so someone else opens it.")).toBeVisible();
  await expect(upgrade.getByRole("button", { name: "Open pull request" })).toHaveCount(0);
  expect(writes).toEqual([{ path: "/api/actions/risk-tickets/ticket-1/pull-requests", body: null }]);
});

test("a response Aegis couldn't automate a pull request for says why where the pull request would be", async ({ page }) => {
  const reason = "hono 4.12.7 comes in through shadcn; re-import the service's SBOM from its repository so Aegis can "
    + "tell which lockfile resolves it and override it there, or upgrade shadcn or pin hono by hand";
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [ticket({ pullRequests: [], evidence: ["Impact score 91.0 (CRITICAL)", `Not automated: ${reason}`] })],
  });
  await page.goto("/responses/tickets/ticket-1");
  const upgrade = page.getByRole("region", { name: "Upgrade pull request" });

  await expect(upgrade.getByText("None: Aegis couldn't automate one when the response was drafted, because:")).toBeVisible();
  await expect(upgrade.getByRole("listitem")).toHaveText([reason]);
  // Once the cause is fixed, asking again drafts it.
  await upgrade.getByRole("button", { name: "Check again" }).click();
  await expect(upgrade.getByText("Awaiting decision")).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/risk-tickets/ticket-1/pull-requests", body: null }]);
});

test("until Jira is connected with a project, approving isn't offered and the page says what is missing", async ({ page }) => {
  await fakeBff(page, "ANALYST", {
    tickets: [ticket()],
    jira: { ...JIRA, connected: false, siteName: null, siteUrl: null, projectKey: null, issueType: null },
  });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("Jira isn't connected to this organization yet", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
});

test("a compliance admin audits responses but neither drafts nor decides them", async ({ page }) => {
  await fakeBff(page, "COMPLIANCE_ADMIN", { tickets: [ticket({ serviceId: "another-service" })] });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You audit these decisions rather than make them.", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Upgrade pull request" }).getByRole("button")).toHaveCount(0);

  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByText("An analyst or this service's owner can draft a response.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response" })).toHaveCount(0);
});

test("a service owner drafts and decides for the services they own, and only those", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", {
    owners: [{ id: "owner-row", userId: "me", name: "Me" }],
    tickets: [ticket(), ticket({ id: "ticket-3", serviceId: "another-service", serviceName: "Ledger" })],
  });
  await page.goto("/responses/tickets/ticket-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();

  await page.goto("/responses/tickets/ticket-3");
  await expect(decision.getByText("An analyst or one of this service's owners approves or dismisses it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("the ServiceNow fields can be copied for an organization that tracks risk there", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await fakeBff(page, "ANALYST", { tickets: [ticket()] });
  await page.goto("/responses/tickets/ticket-1");
  const serviceNow = page.getByRole("region", { name: "ServiceNow incident" });
  await expect(serviceNow.locator("pre")).toContainText('"cmdb_ci": "Payments API"');

  await serviceNow.getByRole("button", { name: "Copy fields" }).click();
  const copied = JSON.parse(await page.evaluate(() => navigator.clipboard.readText()));
  expect(copied).toMatchObject({ short_description: "CVE-2021-44228 affects Payments API", impact: "1" });
});

test("the review queue holds responses that need a person, history holds the decided ones", async ({ page }) => {
  const filedWithFailedPr = ticket({
    id: "ticket-4", title: "CVE-2024-39338 affects Payments API", status: "FILED", issueKey: "SEC-9",
    issueUrl: "https://acme.atlassian.net/browse/SEC-9", decidedBy: "analyst-1", decidedAt: "2026-09-26T05:00:00Z",
    pullRequests: [pullRequest({ id: "pr-4", riskTicketId: "ticket-4", status: "FAILED", failureMessage: "GitHub is unavailable" })],
  });
  const done = outage({
    id: "ticket-5", status: "FILED", issueKey: "SEC-10", issueUrl: "https://acme.atlassian.net/browse/SEC-10",
    decidedBy: "analyst-1", decidedAt: "2026-09-26T06:00:00Z",
  });
  const { listed } = await fakeBff(page, "ANALYST", { tickets: [ticket(), filedWithFailedPr, done] });

  await page.goto("/responses/review");
  const waiting = page.getByRole("region", { name: "Responses awaiting a decision" });
  await expect(waiting.getByRole("link", { name: "CVE-2021-44228 affects Payments API" }))
    .toHaveAttribute("href", "/responses/tickets/ticket-1");
  // Filed, but its pull request failed: still someone's to act on.
  await expect(waiting.getByRole("link", { name: "CVE-2024-39338 affects Payments API" })).toBeVisible();
  await expect(waiting.getByText("Opening failed")).toBeVisible();
  await expect(waiting.getByText("SEC-10")).toHaveCount(0);

  await page.goto("/responses/history");
  const decided = page.getByRole("region", { name: "Responses" });
  await expect(decided.getByRole("link", { name: "Vendor outage affects Payments API: Card API outage" }))
    .toHaveAttribute("href", "/responses/tickets/ticket-5");
  await expect(decided.getByRole("link", { name: "SEC-10" })).toHaveAttribute("href", "https://acme.atlassian.net/browse/SEC-10");
  await expect(decided.getByRole("link", { name: "CVE-2024-39338 affects Payments API" })).toHaveCount(0);
  const queries = distinct(listed).map((query) => new URLSearchParams(query));
  expect(queries.some((query) => query.get("pending") === "true" && !query.has("status"))).toBe(true);
  expect(queries.some((query) => query.get("pending") === "false" && query.get("status") === "FILED,DISMISSED")).toBe(true);
});

test("a reviewer edits the draft before deciding, and the draft as first written stays on record", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { tickets: [outage()] });
  await page.goto("/responses/tickets/ticket-2");
  const content = page.getByRole("region", { name: "Ticket" });

  await content.getByRole("button", { name: "Edit draft" }).click();
  const form = content.getByRole("form", { name: "Edit the draft" });
  await form.getByLabel("Summary").fill("Card payments fail while Acme Pay is down.");
  await form.getByLabel("Recommended actions, one per line").fill("Switch to the fallback provider.\n\n  Tell support. ");
  await form.getByRole("button", { name: "Save draft" }).click();

  await expect(content.getByText("Card payments fail while Acme Pay is down.")).toBeVisible();
  await expect(content.getByText("Edited by you", { exact: false })).toBeVisible();
  await content.getByText("The draft as first written").click();
  await expect(content.getByText("Payments API runs a vulnerable log4j-core.")).toBeVisible();
  expect(writes).toEqual([{
    path: "/api/actions/risk-tickets/ticket-2/edit",
    body: {
      version: 0, summary: "Card payments fail while Acme Pay is down.", risk: "Remote code execution.",
      impact: "Takeover of the service.", actions: ["Switch to the fallback provider.", "Tell support."],
    },
  }]);
});

test("overriding the priority and due date needs a reason, and Aegis's assessment stays on record", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { tickets: [ticket()] });
  await page.goto("/responses/tickets/ticket-1");
  const content = page.getByRole("region", { name: "Ticket" });

  await content.getByRole("button", { name: "Override priority" }).click();
  await page.getByLabel("Priority", { exact: true }).selectOption("LOW");
  await page.getByLabel("Due date (empty: none)").fill("");
  const override = page.getByRole("button", { name: "Override", exact: true });
  await expect(override).toBeDisabled();
  await page.getByLabel("Why is Aegis's assessment wrong here?").fill("The vulnerable lookup is disabled by configuration.");
  await override.click();

  await expect(content.getByText("Priority and due date overridden")).toBeVisible();
  await expect(content.getByText("Aegis assessed Highest priority, due", { exact: false })).toBeVisible();
  await expect(content.getByText("The vulnerable lookup is disabled by configuration.")).toBeVisible();
  const lifecycle = page.getByRole("list", { name: "Lifecycle" });
  await expect(lifecycle).toContainText("Overridden");

  // Approving names the version that includes the override.
  const decision = page.getByRole("region", { name: "Decision" });
  await decision.getByRole("button", { name: "Approve", exact: true }).click();
  await page.getByRole("button", { name: "Approve response", exact: true }).click();
  await expect(decision.getByText("Filed in Jira")).toBeVisible();
  await expect(lifecycle.locator('[aria-current="step"]')).toHaveCount(0);
  await expect(lifecycle).toContainText("Filed as SEC-7.");
  expect(writes).toEqual([
    {
      path: "/api/actions/risk-tickets/ticket-1/override",
      body: { version: 0, priority: "LOW", dueDate: null, reason: "The vulnerable lookup is disabled by configuration." },
    },
    { path: "/api/actions/risk-tickets/ticket-1/approve", body: { version: 1 } },
  ]);
});

test("the lifecycle shows where a response stands, and dismissing it with a reason closes it", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", { tickets: [outage()] });
  await page.goto("/responses/tickets/ticket-2");
  const lifecycle = page.getByRole("list", { name: "Lifecycle" });
  await expect(lifecycle.getByRole("listitem")).toHaveCount(4);
  await expect(lifecycle.locator('[aria-current="step"]')).toContainText("In review");
  await expect(lifecycle).toContainText("Approved or overridden");
  await expect(lifecycle).toContainText("Actioned or closed");

  const decision = page.getByRole("region", { name: "Decision" });
  await decision.getByRole("button", { name: "Dismiss", exact: true }).click();
  await expect(page.getByRole("button", { name: "Dismiss response" })).toBeDisabled();
  await page.getByLabel("Why is no response needed?").fill("Acme Pay no longer takes card payments.");
  await page.getByRole("button", { name: "Dismiss response" }).click();

  await expect(decision.getByText("Dismissed by you", { exact: false })).toBeVisible();
  await expect(lifecycle).toContainText("Closed");
  await expect(lifecycle).toContainText("Not approved.");
  expect(writes).toEqual([{
    path: "/api/actions/risk-tickets/ticket-2/dismiss",
    body: { reason: "Acme Pay no longer takes card payments.", version: 0 },
  }]);
});

test("a decision on a response someone changed meanwhile is refused, and the page shows it as it is now", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [outage()],
    changedMeanwhile: { summary: "Rewritten by a colleague." },
  });
  await page.goto("/responses/tickets/ticket-2");
  const decision = page.getByRole("region", { name: "Decision" });
  await decision.getByRole("button", { name: "File ticket", exact: true }).click();
  await page.getByRole("button", { name: "File ticket", exact: true }).last().click();

  await expect(page.getByText("This response changed after you opened it", { exact: false })).toBeVisible();
  await expect(page.getByRole("region", { name: "Ticket" }).getByText("Rewritten by a colleague.")).toBeVisible();
  await expect(decision.getByText("Awaiting a decision")).toBeVisible();
  expect(writes).toEqual([{ path: "/api/actions/risk-tickets/ticket-2/approve", body: { version: 0 } }]);
});

test("only someone who decides may edit or override, and only before the decision", async ({ page }) => {
  await fakeBff(page, "COMPLIANCE_ADMIN", { tickets: [ticket()] });
  await page.goto("/responses/tickets/ticket-1");
  const content = page.getByRole("region", { name: "Ticket" });
  await expect(content.getByRole("heading", { name: "Ticket" })).toBeVisible();
  await expect(content.getByRole("button")).toHaveCount(0);

  await fakeBff(page, "ANALYST", {
    tickets: [ticket({ status: "FILED", stage: "ACTIONED", issueKey: "SEC-7", decidedBy: "analyst-1" })],
  });
  await page.goto("/responses/tickets/ticket-1");
  await expect(content.getByRole("heading", { name: "Ticket" })).toBeVisible();
  await expect(content.getByRole("button", { name: "Edit draft" })).toHaveCount(0);
  await expect(content.getByRole("button", { name: "Override priority" })).toHaveCount(0);
});

test("while the draft has unsaved edits, approving and dismissing wait for them", async ({ page }) => {
  await fakeBff(page, "ANALYST", { tickets: [outage()] });
  await page.goto("/responses/tickets/ticket-2");
  const content = page.getByRole("region", { name: "Ticket" });
  const decision = page.getByRole("region", { name: "Decision" });

  await content.getByRole("button", { name: "Edit draft" }).click();
  await expect(decision.getByText("Save or cancel your edit to the draft before deciding.")).toBeVisible();
  await expect(decision.getByRole("button", { name: "File ticket", exact: true })).toBeDisabled();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeDisabled();

  await content.getByRole("button", { name: "Cancel" }).click();
  await expect(decision.getByRole("button", { name: "File ticket", exact: true })).toBeEnabled();
});

test("an edit saved after someone else changed the response is refused, not written over theirs", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [outage()],
    changedMeanwhile: { summary: "Rewritten by a colleague." },
  });
  await page.goto("/responses/tickets/ticket-2");
  const content = page.getByRole("region", { name: "Ticket" });

  await content.getByRole("button", { name: "Edit draft" }).click();
  const form = content.getByRole("form", { name: "Edit the draft" });
  await form.getByLabel("Summary").fill("My summary.");
  await form.getByRole("button", { name: "Save draft" }).click();

  await expect(page.getByText("This response changed after you opened it", { exact: false })).toBeVisible();
  // The editor keeps what was typed, but can't save it over the colleague's change.
  await expect(form.getByRole("alert")).toContainText("Someone changed this response while you were editing it");
  await expect(form.getByLabel("Summary")).toHaveValue("My summary.");
  await expect(form.getByRole("button", { name: "Save draft" })).toBeDisabled();
  await form.getByRole("button", { name: "Cancel" }).click();
  await expect(content.getByText("Rewritten by a colleague.")).toBeVisible();
  expect(writes).toHaveLength(1);
});

test("an override's reason can change on its own, and withdrawing it restores Aegis's assessment", async ({ page }) => {
  const { writes } = await fakeBff(page, "ANALYST", {
    tickets: [ticket({
      priority: "LOW", dueDate: null, version: 1,
      override: { assessedPriority: "HIGHEST", assessedDueDate: "2026-10-03", reason: "Mitigated by the WAF rule.",
        by: "analyst-1", at: "2026-09-26T08:30:00Z" },
    })],
  });
  await page.goto("/responses/tickets/ticket-1");
  const content = page.getByRole("region", { name: "Ticket" });

  await content.getByRole("button", { name: "Change override" }).click();
  const reason = page.getByLabel("Why is Aegis's assessment wrong here?");
  const save = page.getByRole("button", { name: "Override", exact: true });
  await expect(reason).toHaveValue("Mitigated by the WAF rule.");
  await expect(save).toBeDisabled();
  // Aegis's own priority and due date aren't an override: that is withdrawing it.
  await page.getByLabel("Priority", { exact: true }).selectOption("HIGHEST");
  await page.getByLabel("Due date (empty: none)").fill("2026-10-03");
  await expect(page.getByText("withdraw the override instead", { exact: false })).toBeVisible();
  await expect(save).toBeDisabled();
  await page.getByLabel("Priority", { exact: true }).selectOption("LOW");
  await page.getByLabel("Due date (empty: none)").fill("");
  await reason.fill("The WAF rule blocks the exploit path.");
  await save.click();
  await expect(content.getByText("The WAF rule blocks the exploit path.")).toBeVisible();

  await content.getByRole("button", { name: "Withdraw override" }).click();
  await expect(content.getByText("Priority and due date overridden")).toHaveCount(0);
  await expect(content.getByText("Highest", { exact: true })).toBeVisible();
  expect(writes).toEqual([
    {
      path: "/api/actions/risk-tickets/ticket-1/override",
      body: { version: 1, priority: "LOW", dueDate: null, reason: "The WAF rule blocks the exploit path." },
    },
    { path: "/api/actions/risk-tickets/ticket-1/override/withdraw", body: { version: 2 } },
  ]);
});
