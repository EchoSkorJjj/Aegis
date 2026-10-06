import { expect, test } from "@playwright/test";
import { fakeBff, OTHER, OWNER, TICKET } from "./helpers/findings";

test("material changes show ranked scores, services and owners, with server paging", async ({ page }) => {
  const { listed } = await fakeBff(page);
  await page.goto("/findings");
  const queue = page.getByRole("region", { name: "Material findings" });
  const rows = queue.getByRole("row").filter({ has: page.getByRole("cell") });
  await expect(rows).toHaveCount(20);
  await expect(rows.first()).toContainText("90.0");
  await expect(rows.first()).toContainText("Morgan Owner");
  await expect(rows.first().getByRole("link", { name: "Card API outage" })).toHaveAttribute("href", "/findings/signal-1");
  await page.screenshot({ path: test.info().outputPath("findings-queue.png"), fullPage: true });
  await page.getByRole("button", { name: "Page 2", exact: true }).click();
  await expect(rows).toHaveCount(3);
  expect(listed.at(-1)?.get("page")).toBe("1");
  await page.getByLabel("Owner", { exact: true }).selectOption(OWNER.id);
  await expect(rows).toHaveCount(12);
  expect(listed.at(-1)?.get("page")).toBe("0");
  expect(listed.at(-1)?.get("ownerId")).toBe(OWNER.id);
  await page.getByLabel("Service", { exact: true }).selectOption(OTHER.id);
  await expect(queue).toContainText("No material findings match these filters.");
  await page.getByRole("button", { name: "Clear filters" }).click();
  await expect(rows).toHaveCount(20);
});

test("my services uses the authenticated ownership scope and explains no assignments", async ({ page }) => {
  const { listed } = await fakeBff(page, { role: "SERVICE_OWNER", owned: 0 });
  await page.goto("/findings/mine");
  await expect(page.getByText("You aren't assigned as an owner of any service.", { exact: false })).toBeVisible();
  expect(listed.at(-1)?.get("mine")).toBe("true");
});

test("failed queue requests are visible and can be retried", async ({ page }) => {
  const { recover } = await fakeBff(page, { fail: true });
  await page.goto("/findings");
  await expect(page.getByText("Could not load findings. Please try again.")).toBeVisible();
  recover();
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByRole("link", { name: "Card API outage", exact: true })).toBeVisible();
});

test("non-domain roles cannot fetch findings", async ({ page }) => {
  const { listed } = await fakeBff(page, { role: "ORG_ADMIN" });
  await page.goto("/findings");
  await expect(page.getByText("You don't have access to findings", { exact: false })).toBeVisible();
  expect(listed).toHaveLength(0);
});

test("review and approve keeps drafted decisions and links to material triage", async ({ page }) => {
  await fakeBff(page);
  await page.goto("/responses/review");
  await expect(page.getByRole("link", { name: "Triage material findings →" })).toHaveAttribute("href", "/findings");
  await expect(page.getByRole("region", { name: "Recommended upgrades" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "Responses awaiting a decision" })).toContainText(TICKET.title);
});
