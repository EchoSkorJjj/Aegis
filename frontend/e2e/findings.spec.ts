import { expect, test } from "@playwright/test";
import { fakeBff, LEDGER, OTHER, OWNER, TICKET } from "./helpers/findings";

test("the triage queue ranks vulnerable dependencies and outages with their fix and response", async ({ page }) => {
  const { drafts } = await fakeBff(page);
  await page.goto("/findings");
  const queue = page.getByRole("region", { name: "Items to respond to" });
  const rows = queue.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(20);

  // A vendor outage still in progress, with the response already drafted for it: linked, not offered again.
  const outage = rows.first();
  await expect(outage.getByRole("link", { name: "Card API outage" })).toHaveAttribute("href", "/findings/signal-1");
  await expect(outage).toContainText("Vendor outage · Acme Pay · investigating");
  await expect(outage.getByRole("link", { name: "Status page ↗" })).toHaveAttribute("href", "https://status.example.test/incident-1");
  await expect(outage).toContainText("Morgan Owner");
  await expect(outage).toContainText("90.0");
  await expect(outage).toContainText("Critical");
  // Where each item stands: drafted and in review, or new until someone drafts a response. The stage says it once;
  // the response's own status badge isn't repeated beside it.
  await expect(outage.getByText("In review", { exact: true })).toBeVisible();
  await expect(outage.getByText("Awaiting decision", { exact: true })).toHaveCount(0);
  await expect(outage.getByRole("link", { name: "View response →" })).toHaveAttribute("href", `/responses/tickets/${TICKET.id}`);
  await expect(outage.getByRole("button")).toHaveCount(0);

  // A vulnerable dependency: the version that fixes it, where it is declared, and its item detail.
  const axios = rows.filter({ hasText: "axios" });
  await expect(axios).toContainText("0.21.1 → 1.15.0");
  await expect(axios).toContainText("Fixes CVE-2026-40175 and 1 more");
  await expect(axios).toContainText("client/package.json, server/package.json");
  await expect(axios.getByRole("link", { name: "CVE-2026-40175" })).toHaveAttribute("href", "/findings/signal-axios");
  await expect(axios.getByRole("link", { name: OTHER.name })).toHaveAttribute("href", `/inventory/services/${OTHER.id}`);
  await expect(axios).toContainText("No owners assigned");
  await expect(axios.getByText("New", { exact: true })).toBeVisible();

  // A response someone dismissed closes its item; drafting another reopens it.
  const closed = rows.filter({ hasText: "lib-04" });
  await expect(closed.getByText("Closed", { exact: true })).toBeVisible();
  await expect(closed.getByRole("button", { name: "Draft response" })).toBeVisible();

  // Nothing to automate until the repository is linked: the response is the ticket alone.
  const minimist = rows.filter({ hasText: "minimist" });
  await expect(minimist).toContainText("Ticket only until the service's repository is linked.");
  await expect(minimist.getByRole("link", { name: "Link it →" })).toHaveAttribute("href", "/inventory/services/service-1");
  await expect(minimist.getByRole("button", { name: "Draft response" })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("triage-queue.png"), fullPage: true });

  await axios.getByRole("button", { name: "Draft response" }).click();
  await expect(page).toHaveURL(/\/responses\/tickets\/ticket-new$/);
  expect(drafts).toEqual([{ changeSignalId: "signal-axios", serviceId: OTHER.id }]);
});

test("the triage queue filters by service, owner, ecosystem and impact, paged by the server", async ({ page }) => {
  const { listed } = await fakeBff(page);
  await page.goto("/findings");
  const queue = page.getByRole("region", { name: "Items to respond to" });
  const rows = queue.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(20);

  await page.getByRole("button", { name: "Page 2", exact: true }).click();
  await expect(rows).toHaveCount(3);
  expect(listed.at(-1)?.get("page")).toBe("1");

  await page.getByLabel("Owner", { exact: true }).selectOption(OWNER.id);
  await expect(rows).toHaveCount(12);
  expect(listed.at(-1)?.get("page")).toBe("0");
  expect(listed.at(-1)?.get("ownerId")).toBe(OWNER.id);
  await page.getByLabel("Ecosystem", { exact: true }).selectOption("maven");
  await expect(rows).toHaveCount(3);
  expect(listed.at(-1)?.get("ecosystem")).toBe("maven");
  await page.getByLabel("Impact", { exact: true }).selectOption("MEDIUM");
  await expect(queue).toContainText("Nothing matches these filters.");
  expect(listed.at(-1)?.get("tier")).toBe("MEDIUM");

  await page.getByRole("button", { name: "Clear", exact: true }).click();
  await expect(rows).toHaveCount(20);
  await page.getByLabel("Impact", { exact: true }).selectOption("CRITICAL");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("Card API outage");
  await page.getByLabel("Service", { exact: true }).selectOption(OTHER.id);
  await expect(queue).toContainText("Nothing matches these filters.");
  expect(listed.at(-1)?.get("serviceId")).toBe(OTHER.id);
});

test("the triage queue keeps its filters in the URL, so Clear, a reload and a shared link all agree", async ({ page }) => {
  const { listed } = await fakeBff(page);
  // Where the dashboard's Unassigned items count lands.
  await page.goto("/findings?open=true&unassigned=true");
  const open = page.getByRole("checkbox", { name: "Open items only" });
  const unassigned = page.getByRole("checkbox", { name: "Unassigned only" });
  const impact = page.getByLabel("Impact", { exact: true });
  await expect(open).toBeChecked();
  await expect(unassigned).toBeChecked();

  await unassigned.uncheck();
  await expect(page).toHaveURL(/\/findings\?open=true$/);
  await expect.poll(() => listed.at(-1)?.get("unassigned")).toBeNull();
  await impact.selectOption("HIGH");
  await expect(page).toHaveURL(/\/findings\?tier=HIGH&open=true$/);
  await page.reload();
  await expect(open).toBeChecked();
  await expect(unassigned).not.toBeChecked();
  await expect(impact).toHaveValue("HIGH");

  await page.getByRole("button", { name: "Clear", exact: true }).click();
  await expect(page).toHaveURL(/\/findings$/);
  await expect(open).not.toBeChecked();
  await page.reload();
  await expect(open).not.toBeChecked();
  await expect(impact).toHaveValue("");
  await expect.poll(() => listed.at(-1)?.get("open")).toBeNull();
  expect(listed.at(-1)?.get("tier")).toBeNull();
});

test("in the triage queue, an owner drafts only for their own services", async ({ page }) => {
  await fakeBff(page, { role: "SERVICE_OWNER" });
  await page.goto("/findings");
  const rows = page.getByRole("region", { name: "Items to respond to" }).getByRole("row").filter({ has: page.getByRole("cell") });
  const axios = rows.filter({ hasText: "axios" });
  await expect(axios).toContainText("An analyst or the service's owner can draft a response");
  await expect(axios.getByRole("button")).toHaveCount(0);
  await expect(rows.filter({ hasText: "minimist" }).getByRole("button", { name: "Draft response" })).toBeVisible();
});

test("My services shows an owner what each of their services needs, then its items", async ({ page }) => {
  const { listed } = await fakeBff(page, { role: "SERVICE_OWNER", owned: 2 });
  await page.goto("/findings/mine");
  await expect(page.getByRole("heading", { name: "My services", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "My services", exact: true })).toBeVisible();

  const yours = page.getByRole("region", { name: "Your services", exact: true });
  const payments = yours.getByRole("row").filter({ hasText: "Payments API" });
  await expect(payments).toContainText("Critical criticality · Internet-facing");
  await expect(payments).toContainText("90.0");
  await expect(payments).toContainText("12 items · 11 with no response yet");
  await expect(payments.getByRole("link", { name: "1 response →" })).toHaveAttribute("href", "/responses/review");
  await expect(payments).toContainText("SBOM imported from its repository");
  await expect(payments).toContainText("github.com/acme/payments");
  await expect(payments.getByRole("link", { name: "Set it up on the service's page →" })).toHaveCount(0);

  // What only its owners keep up: without an SBOM or a repository, Aegis sees less and drafts less.
  const ledger = yours.getByRole("row").filter({ hasText: LEDGER.name });
  await expect(ledger).toContainText("Nothing to respond to");
  await expect(ledger).toContainText("No SBOM imported yet");
  await expect(ledger).toContainText("No repository linked");
  await expect(ledger.getByRole("link", { name: "Set it up on the service's page →" }))
    .toHaveAttribute("href", `/inventory/services/${LEDGER.id}`);

  // Then the items in those services, worst first; they are all the owner's, so there is no owner to filter by.
  const rows = page.getByRole("region", { name: "Items to respond to" }).getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(12);
  await expect(rows.filter({ hasText: OTHER.name })).toHaveCount(0);
  await expect(page.getByLabel("Owner", { exact: true })).toHaveCount(0);
  expect(listed.at(-1)?.get("mine")).toBe("true");
  await page.screenshot({ path: test.info().outputPath("my-services.png"), fullPage: true });

  await payments.getByRole("button", { name: "Show them ↓" }).click();
  await expect.poll(() => listed.at(-1)?.get("serviceId")).toBe("service-1");
  expect(listed.at(-1)?.get("mine")).toBe("true");
});

test("only a service owner has My services; anyone else is pointed to the triage queue", async ({ page }) => {
  const { listed, overviews } = await fakeBff(page, { role: "ANALYST" });
  await page.goto("/findings");
  await expect(page.getByRole("link", { name: "Triage queue", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "My services", exact: true })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Items to respond to" }).getByRole("row").filter({ has: page.getByRole("cell") }))
    .toHaveCount(20);
  const requests = listed.length;

  await page.goto("/findings/mine");
  await expect(page.getByText("My services is a service owner's page", { exact: false })).toBeVisible();
  await expect(page.getByRole("link", { name: "triage queue", exact: true })).toHaveAttribute("href", "/findings");
  expect(overviews).toHaveLength(0);
  expect(listed).toHaveLength(requests);
});

test("My services tells an owner with no services so, instead of showing an empty list", async ({ page }) => {
  const { listed } = await fakeBff(page, { role: "SERVICE_OWNER", owned: 0 });
  await page.goto("/findings/mine");
  await expect(page.getByText("You aren't assigned as the owner of any service yet.", { exact: false })).toBeVisible();
  await expect(page.getByRole("link", { name: "triage queue", exact: true })).toHaveAttribute("href", "/findings");
  expect(listed).toHaveLength(0);
});

test("a failed queue request is shown and can be retried", async ({ page }) => {
  const { recover } = await fakeBff(page, { fail: true });
  await page.goto("/findings");
  await expect(page.getByText("Could not load the triage queue. Please try again.")).toBeVisible();
  recover();
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("link", { name: "Card API outage", exact: true })).toBeVisible();
});

test("roles without domain access never fetch the triage queue", async ({ page }) => {
  const { listed } = await fakeBff(page, { role: "ORG_ADMIN" });
  await page.goto("/findings");
  await expect(page.getByText("You don't have access to the triage queue", { exact: false })).toBeVisible();
  expect(listed).toHaveLength(0);
});

test("Review & approve keeps the drafted responses and links back to the triage queue", async ({ page }) => {
  await fakeBff(page);
  await page.goto("/responses/review");
  await expect(page.getByRole("link", { name: "Draft responses from the triage queue →" })).toHaveAttribute("href", "/findings");
  await expect(page.getByRole("region", { name: "Recommended upgrades" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Responses awaiting a decision" })).toContainText(TICKET.title);
});
