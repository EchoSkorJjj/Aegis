import { expect, type Browser, type Page } from "@playwright/test";

import { signIn, signUp } from "../e2e/helpers";

export const PASSWORD = "Journey-Passw0rd!";
export const CVE = "CVE-2026-90001";
/** The package fake-upstreams.mjs publishes the advisory for. */
export const PACKAGE = "com.aegisjourney:journey-lib";

/** A browser of its own for one person, with an account that isn't signed in yet. */
export async function newPerson(browser: Browser, name: string, email: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await signUp(page, { name, email, password: PASSWORD });
  return page;
}

/** An organization admin adds an existing account to the organization, with one domain role. */
export async function invite(admin: Page, email: string, role: "Analyst" | "Service Owner"): Promise<void> {
  await admin.goto("/admin/users");
  await admin.getByRole("button", { name: "Invite user" }).click();
  await admin.getByLabel("Email").fill(email);
  await admin.getByRole("button", { name: role, exact: true }).click();
  await admin.getByRole("button", { name: "Send invite" }).click();
  await expect(admin.getByRole("cell", { name: email })).toBeVisible();
}

export async function signInAs(page: Page, email: string): Promise<void> {
  await signIn(page, email, PASSWORD);
}

async function newInventoryRow(page: Page, entity: string, fill: () => Promise<void>) {
  await page.getByRole("button", { name: `New ${entity}` }).click();
  await fill();
  await page.getByRole("button", { name: `Create ${entity}` }).click();
  await expect(page.getByText(`${entity[0].toUpperCase()}${entity.slice(1)} created`)).toBeVisible();
}

/** An analyst registers a critical, internet-facing service using the vulnerable package; ends on its page. */
export async function registerVulnerableService(analyst: Page, serviceName: string): Promise<void> {
  await analyst.goto("/inventory/components");
  await newInventoryRow(analyst, "component", async () => {
    await analyst.getByPlaceholder("npm").fill("maven");
    await analyst.getByPlaceholder("log4j-core").fill(PACKAGE);
    await analyst.getByPlaceholder("2.14.1").fill("1.2.3");
  });

  await analyst.goto("/inventory/services");
  await newInventoryRow(analyst, "service", async () => {
    await analyst.getByPlaceholder("Checkout API").fill(serviceName);
    const form = analyst.locator("form").filter({ has: analyst.getByPlaceholder("Checkout API") });
    await form.getByLabel("Criticality").selectOption("CRITICAL");
    await form.getByLabel("Exposure").selectOption("INTERNET_FACING");
  });

  await analyst.getByRole("link", { name: serviceName }).click();
  await analyst.getByRole("combobox", { name: "Link an existing component…" }).fill("journey-lib");
  await analyst.getByRole("listbox").getByRole("option", { name: /journey-lib/ }).click();
  await expect(analyst.getByText("Component linked")).toBeVisible();
}

/** Waits for the advisory to reach the triage queue: ingestion polls every few seconds. */
export async function openQueueWithItem(page: Page): Promise<void> {
  await expect(async () => {
    await page.goto("/findings");
    await expect(page.getByRole("main").getByText(PACKAGE).first()).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 90_000 });
}
