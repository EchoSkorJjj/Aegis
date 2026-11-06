import { expect, test, type Page } from "@playwright/test";

import { createOrg, signIn } from "../e2e/helpers";
import { CVE, invite, newPerson, openQueueWithItem, PASSWORD, registerVulnerableService, signInAs } from "./helpers";

// The other journeys the product is for, against the real backend, sharing one organization: two analysts, an
// assigned service owner and an admin, with one vulnerable service.
test.describe.configure({ mode: "serial" });

const run = `${Date.now()}`;
const orgName = `Core org ${run}`;
const serviceName = `Core ledger ${run}`;
const email = (who: string) => `core-${who}-${run}@example.com`;

let admin: Page;
let analyst: Page;
let secondAnalyst: Page;
let owner: Page;

test.beforeAll(async ({ browser }) => {
  test.setTimeout(420_000);
  admin = await newPerson(browser, "Core Admin", email("admin"));
  analyst = await newPerson(browser, "Core Analyst", email("analyst"));
  secondAnalyst = await newPerson(browser, "Core Second Analyst", email("second"));
  owner = await newPerson(browser, "Core Owner", email("owner"));

  await signIn(admin, email("admin"), PASSWORD);
  await createOrg(admin, orgName);
  await invite(admin, email("analyst"), "Analyst");
  await invite(admin, email("second"), "Analyst");
  await invite(admin, email("owner"), "Service Owner");

  await signInAs(analyst, email("analyst"));
  await signInAs(secondAnalyst, email("second"));
  await signInAs(owner, email("owner"));

  await registerVulnerableService(analyst, serviceName);
  await analyst.getByRole("combobox", { name: "Assign a member with the Service Owner role…" }).fill("Core Owner");
  await analyst.getByRole("listbox").getByRole("option", { name: /Core Owner/ }).click();
  await expect(analyst.getByText(/owner (assigned|linked)/i)).toBeVisible();
});

test("the assigned owner finds the item in My services, and is told about it", async () => {
  await expect(async () => {
    await owner.goto("/findings/mine");
    await expect(owner.getByRole("main").getByText(serviceName).first()).toBeVisible({ timeout: 5_000 });
    await expect(owner.getByRole("button", { name: /Notifications, \d+ unread/ })).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 120_000 });

  await owner.getByRole("button", { name: /Notifications, \d+ unread/ }).click();
  await expect(owner.getByRole("region", { name: "Notifications", exact: true })).toContainText(CVE);
});

test("the owner drafts a response and dismisses it with a reason, which is on the record", async () => {
  const reason = `Compensating control in place (${run})`;
  await openQueueWithItem(owner);
  await owner.getByRole("button", { name: "Draft response" }).click();
  await owner.waitForURL(/\/responses\/tickets\//);

  await owner.getByRole("button", { name: "Dismiss", exact: true }).click();
  const popup = owner.getByRole("dialog");
  await popup.getByRole("textbox").fill(reason);
  await popup.getByRole("button", { name: /^Dismiss/ }).click();
  await expect(owner.getByRole("main").getByText(reason).first()).toBeVisible();

  await analyst.goto("/reports/audit");
  const trail = analyst.getByRole("main").getByRole("table");
  await expect(trail.getByText(/dismissed/i).first()).toBeVisible();
});

test("a regulator memo is drafted by one analyst and only another may make it final", async () => {
  await openQueueWithItem(analyst);
  await analyst.getByRole("main").getByRole("link", { name: new RegExp(CVE) }).first().click();
  await analyst.waitForURL(/\/findings\/[0-9a-f-]+/);

  await analyst.getByRole("button", { name: "Draft regulator memo" }).click();
  await analyst.waitForURL(/\/responses\/memos\//);
  await expect(analyst.getByText("You drafted this, so another analyst approves it.")).toBeVisible();
  await expect(analyst.getByRole("button", { name: "Approve", exact: true })).toBeHidden();

  await secondAnalyst.goto(new URL(analyst.url()).pathname);
  await secondAnalyst.getByRole("button", { name: "Approve", exact: true }).click();
  await secondAnalyst.getByRole("dialog").getByRole("button", { name: "Make final" }).click();
  await expect(secondAnalyst.getByText(/Approved by/).first()).toBeVisible();
});

test("a service's evidence downloads as CSV, holding its decisions", async () => {
  await analyst.goto("/reports/evidence");
  await analyst.getByLabel("Service", { exact: true }).selectOption({ label: serviceName });
  const download = analyst.waitForEvent("download");
  await analyst.getByRole("button", { name: "Download CSV" }).click();
  const file = await download;
  const chunks: Buffer[] = [];
  for await (const chunk of await file.createReadStream()) chunks.push(chunk as Buffer);
  const csv = Buffer.concat(chunks).toString("utf8");
  expect(csv).toContain(serviceName);
  expect(csv).toMatch(/DISMISSED/);
});

test("another organization sees none of it", async ({ browser }) => {
  const otherAdmin = await newPerson(browser, "Other Admin", email("other-admin"));
  await signIn(otherAdmin, email("other-admin"), PASSWORD);
  await createOrg(otherAdmin, `Other org ${run}`);
  await invite(otherAdmin, email("second"), "Analyst");

  const servicePath = await (async () => {
    await analyst.goto("/inventory/services");
    await analyst.getByRole("link", { name: serviceName }).click();
    await analyst.waitForURL(/\/inventory\/services\/[0-9a-f-]+/);
    return new URL(analyst.url()).pathname;
  })();

  // The second analyst, a member of both, switches organization.
  await secondAnalyst.goto("/dashboard");
  await secondAnalyst.reload();
  await secondAnalyst.getByRole("button", { name: `${orgName} Aegis`, exact: true }).click();
  await secondAnalyst.getByRole("menuitem", { name: `Other org ${run}` }).click();
  await expect(secondAnalyst.getByRole("button", { name: `Other org ${run} Aegis`, exact: true })).toBeVisible();

  await secondAnalyst.goto("/inventory/services");
  await expect(secondAnalyst.getByText(/No services yet/)).toBeVisible();
  await secondAnalyst.goto(servicePath);
  await expect(secondAnalyst.getByRole("main").getByText(serviceName)).toHaveCount(0);
  const response = await secondAnalyst.request.get(`/api${servicePath}`);
  expect(response.status()).toBe(404);
});
