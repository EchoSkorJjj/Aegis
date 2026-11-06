import { expect, test } from "@playwright/test";

import { createOrg, signIn } from "../e2e/helpers";
import { CVE, invite, newPerson, openQueueWithItem, PASSWORD, registerVulnerableService, signInAs } from "./helpers";

// The whole product loop against the real backend, with nothing faked but NVD and Atlassian themselves
// (fake-upstreams.mjs): an advisory arrives through the NVD connector; matching and scoring find it in a service;
// the ai-service (stub provider) drafts the response; an analyst approves it; the Jira adapter files it; the
// decision trail records it. See e2e-full-stack/stack.sh for what runs.
const UPSTREAMS = `http://127.0.0.1:${process.env.FAKE_UPSTREAMS_PORT ?? 9099}`;

interface FiledIssue {
  key: string;
  fields: { summary: string };
}

async function filedIssues(): Promise<FiledIssue[]> {
  return (await (await fetch(`${UPSTREAMS}/__test/issues`)).json()) as FiledIssue[];
}

test("from sign-in to an approved response, filed in Jira and on the record", async ({ browser }) => {
  // Advisories are global, so a rerun against the same stack needs only its own organization and service.
  const run = `${Date.now()}`;
  const adminEmail = `journey-admin-${run}@example.com`;
  const analystEmail = `journey-analyst-${run}@example.com`;
  const serviceName = `Journey payments ${run}`;
  const filedBefore = (await filedIssues()).length;

  const analyst = await newPerson(browser, "Journey Analyst", analystEmail);
  const admin = await newPerson(browser, "Journey Admin", adminEmail);

  await test.step("an admin creates the organization and invites the analyst", async () => {
    await signIn(admin, adminEmail, PASSWORD);
    await createOrg(admin, `Journey org ${run}`);
    await invite(admin, analystEmail, "Analyst");
  });

  await test.step("the admin connects Jira through Atlassian's consent", async () => {
    await admin.goto("/admin/integrations");
    await admin.getByRole("button", { name: "Connect Jira" }).click();
    await admin.waitForURL(/\/admin\/integrations\/jira\/callback/);
    await admin.getByRole("link", { name: "Choose the project" }).click();
    const where = admin.getByRole("form", { name: "Where risk tickets go" });
    await where.getByLabel("Project").selectOption("AEG");
    await where.getByLabel("Issue type").selectOption("Task");
    await where.getByRole("button", { name: "Save" }).click();
    await expect(admin.getByLabel("Jira connection")).toContainText("AEG");
  });

  await test.step("the analyst signs in and registers the service and its dependency", async () => {
    await signInAs(analyst, analystEmail);
    await registerVulnerableService(analyst, serviceName);
  });

  await test.step("the advisory reaches the triage queue, and the analyst drafts a response", async () => {
    await openQueueWithItem(analyst);
    await analyst.getByRole("button", { name: "Draft response" }).click();
    await analyst.waitForURL(/\/responses\/tickets\//);
    await expect(analyst.getByRole("heading", { level: 1 })).toContainText(CVE);
  });

  await test.step("the analyst approves it, and only then is it filed", async () => {
    expect((await filedIssues()).length).toBe(filedBefore);

    await analyst.getByRole("button", { name: "File ticket" }).click();
    await analyst.getByRole("dialog").getByRole("button", { name: "File ticket" }).click();
    await expect(analyst.getByText(/Filed as AEG-\d+/).first()).toBeVisible();

    const issues = await filedIssues();
    expect(issues.length).toBe(filedBefore + 1);
    expect(issues.at(-1)!.fields.summary).toBe(`${CVE} affects ${serviceName}`);
  });

  await test.step("every step is on the decision trail", async () => {
    await analyst.goto("/reports/audit");
    const trail = analyst.getByRole("main").getByRole("table");
    await expect(trail.getByText(serviceName).first()).toBeVisible();
    for (const step of [/drafted/i, /approved/i, /filed/i]) {
      await expect(trail.getByText(step).first()).toBeVisible();
    }
  });
});
