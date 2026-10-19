import { expect, test } from "@playwright/test";
import { fakeBff, PR, TICKET } from "./helpers/findings";

test("finding detail joins the change, owners, score reasons, evidence and drafted response", async ({ page }) => {
  await fakeBff(page, { role: "COMPLIANCE_ADMIN" });
  await page.goto("/findings/signal-1");
  await expect(page.getByRole("heading", { name: "Card API outage", exact: true })).toBeVisible();
  await expect(page.getByText("Card processing is experiencing elevated errors.")).toBeVisible();
  await expect(page.getByText("Why this score")).toBeVisible();
  await expect(page.getByText("Morgan Owner", { exact: true })).toBeVisible();
  await expect(page.getByText("Payments API depends on Acme Pay.")).toBeVisible();
  const draft = page.getByRole("region", { name: "Drafted response for Payments API" });
  await expect(draft).toContainText(TICKET.summary);
  await expect(draft).toContainText(TICKET.risk);
  await expect(draft).toContainText(TICKET.impact);
  await expect(draft).toContainText(TICKET.actions[0]);
  await expect(page.getByRole("link", { name: "View response →" })).toHaveAttribute("href", "/responses/tickets/ticket-1");
  await expect(page.getByRole("button", { name: "Draft response", exact: true })).toHaveCount(0);
  await page.screenshot({ path: test.info().outputPath("finding-detail.png"), fullPage: true });
});

test("a vulnerability's drafted pull request shows every manifest edit and inert body text", async ({ page }) => {
  await fakeBff(page, { vulnerability: true, tickets: [{ ...TICKET, signalType: "VULNERABILITY", pullRequests: [PR] }] });
  await page.goto("/findings/signal-1");
  await page.getByText("Upgrade pull request: Upgrade axios", { exact: true }).click();
  await expect(page.getByRole("table", { name: "What the pull request changes" })).toContainText("package.json");
  await expect(page.getByRole("table", { name: "What the pull request changes" })).toContainText("0.21.1 → 1.15.0");
  await expect(page.getByText(PR.body, { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "View pull request →" })).toHaveAttribute("href", "/responses/upgrades/pr-1");
});

test("an assigned owner can draft from detail", async ({ page }) => {
  await fakeBff(page, { role: "SERVICE_OWNER", tickets: [] });
  await page.goto("/findings/signal-1");
  await expect(page.getByRole("button", { name: "Draft response", exact: true })).toBeVisible();
});

test("response failures do not turn an existing draft into an offer to draft again", async ({ page }) => {
  await fakeBff(page, { responseFailure: true });
  await page.goto("/findings/signal-1");
  await expect(page.getByText("Could not load drafted responses.", { exact: false })).toBeVisible();
  await expect(page.getByText("Why this score")).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response", exact: true })).toHaveCount(0);
});

test("a compliance admin reads an undrafted finding without draft controls", async ({ page }) => {
  await fakeBff(page, { role: "COMPLIANCE_ADMIN", tickets: [] });
  await page.goto("/findings/signal-1");
  await expect(page.getByText("An analyst or this service's owner can draft a response.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Draft response", exact: true })).toHaveCount(0);
});

