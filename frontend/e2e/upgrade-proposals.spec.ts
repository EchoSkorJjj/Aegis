import { expect, test, type Page } from "@playwright/test";
import type {
  ChangeImpactResponse,
  EvidenceChain,
  MatchedComponentRef,
  OwnerRef,
  Role,
  ScoreFactor,
  UpgradeProposal,
} from "../src/lib/types";

// Browser contract checks use a fake BFF. Backend RBAC, tenancy and the proposal state machine are
// covered by UpgradeProposalIntegrationTest.
const SIGNAL = "signal-1";
const SERVICE = "service-1";
const LOG4J: MatchedComponentRef = {
  id: "log4j", ecosystem: "maven", name: "org.apache.logging.log4j:log4j-core", version: "2.14.1",
};
const SNAKEYAML: MatchedComponentRef = { id: "snakeyaml", ecosystem: "maven", name: "org.yaml:snakeyaml", version: "1.29" };
const STARTER: MatchedComponentRef = {
  id: "starter", ecosystem: "maven", name: "org.springframework.boot:spring-boot-starter", version: "2.5.0",
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
    matchedComponentCount: 2,
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
      evidence: [chain([LOG4J]), chain([SNAKEYAML, STARTER])],
    }],
    owners: [],
  };
}

function proposal(overrides: Partial<UpgradeProposal> = {}): UpgradeProposal {
  return {
    id: "proposal-1", serviceId: SERVICE, serviceName: "Payments API", changeSignalId: SIGNAL,
    vulnerabilityId: "CVE-2021-44228", componentId: LOG4J.id, ecosystem: "maven", packageName: LOG4J.name,
    fromVersion: "2.14.1", toVersion: "2.17.1", manifestPath: "pom.xml",
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
function distinct(values: (string | null)[]) {
  return [...new Set(values)];
}

type Options = { owners?: OwnerRef[]; proposals?: UpgradeProposal[]; approveFailure?: string };

async function fakeBff(page: Page, role: Role, { owners = [], proposals = [], approveFailure }: Options = {}) {
  const store = [...proposals];
  const writes: { path: string; body: unknown }[] = [];
  const listedStatuses: (string | null)[] = [];
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
      const status = url.searchParams.get("status");
      listedStatuses.push(status);
      const signal = url.searchParams.get("changeSignalId");
      const content = store.filter((candidate) => (!status || status.split(",").includes(candidate.status))
        && (!signal || candidate.changeSignalId === signal));
      return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
    }
    if (path === "/api/actions/upgrade-proposals" && request.method() === "POST") {
      const body = request.postDataJSON();
      writes.push({ path, body });
      const created = proposal({ id: "proposal-new", componentId: body.componentId, manifestPath: body.manifestPath ?? "pom.xml" });
      store.push(created);
      return route.fulfill({ status: 201, json: created });
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
  return { writes, listedStatuses };
}

test("an analyst drafts a direct upgrade from the impact page; a transitive one is explained instead", async ({ page }) => {
  const { writes, listedStatuses } = await fakeBff(page, "ANALYST");
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByRole("heading", { name: "Log4Shell" })).toBeVisible();
  await expect(page.getByText(
    `${SNAKEYAML.name} reaches this service through ${STARTER.name}. Transitive upgrades aren't automated yet`,
  )).toBeVisible();
  expect(distinct(listedStatuses)).toEqual(["PROPOSED,OPENING,OPENED,FAILED"]);

  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toHaveCount(1);
  await page.getByRole("button", { name: "Draft upgrade PR" }).click();
  await page.getByLabel("Manifest path").fill("services/payments/pom.xml");
  await page.getByRole("button", { name: "Draft proposal" }).click();

  await expect(page).toHaveURL(/\/responses\/upgrades\/proposal-new$/);
  await expect(page.getByRole("heading", { name: `Upgrade ${LOG4J.name}` })).toBeVisible();
  expect(writes).toEqual([{
    path: "/api/actions/upgrade-proposals",
    body: { changeSignalId: SIGNAL, serviceId: SERVICE, componentId: LOG4J.id, manifestPath: "services/payments/pom.xml" },
  }]);
});

test("an upgrade already drafted is linked, not offered again", async ({ page }) => {
  await fakeBff(page, "ANALYST", { proposals: [proposal({ status: "OPENED", pullRequestNumber: "42" })] });
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByText("Draft PR open")).toBeVisible();
  await expect(page.getByRole("link", { name: "View proposal →" })).toHaveAttribute("href", "/responses/upgrades/proposal-1");
  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toHaveCount(0);
});

test("a service owner who doesn't own the service sees drafted upgrades but can't draft", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { proposals: [proposal({ status: "OPENED", pullRequestNumber: "42" })] });
  await page.goto(`/advisories/${SIGNAL}`);
  // The drafted proposal showing means the upgrade rows have rendered; no drafting affordance may appear.
  await expect(page.getByRole("link", { name: "View proposal →" })).toBeVisible();
  await expect(page.getByText("Transitive upgrades aren't automated yet", { exact: false })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toHaveCount(0);
});

test("an assigned service owner may draft", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { owners: [{ id: "owner-row", userId: "me", name: "Me" }] });
  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toBeVisible();
});

test("approving opens a draft pull request and the body is shown as text", async ({ page }) => {
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

test("a failed opening shows why, and can be dismissed with a reason", async ({ page }) => {
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

test("a service owner who doesn't own the service can read a proposal but not decide it", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  // Shown only once ownership is known, so the missing buttons are the settled state.
  await expect(decision.getByText("An analyst or one of this service's owners approves or dismisses it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);
});

test("an assigned service owner can decide", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", { owners: [{ id: "owner-row", userId: "me", name: "Me" }], proposals: [proposal()] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();
});

test("whoever drafted a proposal may dismiss it, but someone else approves it", async ({ page }) => {
  await fakeBff(page, "ANALYST", { proposals: [proposal({ createdBy: "me" })] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(page.getByText("Drafted by you", { exact: false })).toBeVisible();
  await expect(decision.getByText("You drafted this, so someone else approves it", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Dismiss", exact: true })).toBeVisible();
  await expect(decision.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);

  await page.goto("/responses/review");
  const drafted = page.getByRole("region", { name: "Drafted, awaiting a decision" });
  await expect(drafted.getByText("You drafted this; someone else approves it")).toBeVisible();
});

test("a compliance admin audits upgrades but neither drafts nor decides them", async ({ page }) => {
  // The proposal is for another component, so the impact page has log4j's upgrade to offer (or not).
  await fakeBff(page, "COMPLIANCE_ADMIN", { proposals: [proposal({ componentId: "another-component" })] });
  await page.goto("/responses/upgrades/proposal-1");
  const decision = page.getByRole("region", { name: "Decision" });
  await expect(decision.getByText("You audit these decisions rather than make them.", { exact: false })).toBeVisible();
  await expect(decision.getByRole("button")).toHaveCount(0);

  await page.goto(`/advisories/${SIGNAL}`);
  await expect(page.getByText("An analyst or this service's owner can draft the upgrade.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft upgrade PR" })).toHaveCount(0);
});

test("the review queue holds what needs a person, history holds what was decided", async ({ page }) => {
  const dismissed = proposal({
    id: "proposal-2", status: "DISMISSED", dismissReason: "Not reachable in our usage",
    decidedBy: "analyst-1", decidedAt: "2026-09-24T12:00:00Z",
  });
  const { listedStatuses } = await fakeBff(page, "ANALYST", { proposals: [proposal(), dismissed] });

  await page.goto("/responses/review");
  const drafted = page.getByRole("region", { name: "Drafted, awaiting a decision" });
  await expect(drafted.getByRole("link", { name: LOG4J.name })).toHaveAttribute("href", "/responses/upgrades/proposal-1");
  await expect(drafted.getByText("Awaiting decision")).toBeVisible();
  await expect(drafted.getByText("Not reachable in our usage")).toHaveCount(0);

  await page.goto("/responses/history");
  const decided = page.getByRole("region", { name: "Upgrade pull requests" });
  await expect(decided.getByRole("link", { name: LOG4J.name })).toHaveAttribute("href", "/responses/upgrades/proposal-2");
  await expect(decided.getByText("Not reachable in our usage")).toBeVisible();
  expect(distinct(listedStatuses)).toEqual(["PROPOSED,FAILED,OPENING", "OPENED,DISMISSED"]);
});
