import { test, expect } from "@playwright/test";

import { createOrg, registerOrgAdmin } from "./helpers";

/**
 * Organization membership (ADR 0001): onboarding into a new org, the workspace switcher, org-admin
 * member management with domain roles, and the platform-admin vs org-admin capability split. These
 * guard the pages that the org-membership work introduced.
 */

test("an org owner can invite a member with a role, change it, and remove them", async ({ page }) => {
  const ts = Date.now();
  // The "Remove from organization" action asks for confirmation via a native dialog.
  page.on("dialog", (dialog) => dialog.accept());

  await registerOrgAdmin(page, {
    name: "Owner One",
    email: `owner-${ts}@example.com`,
    password: "password123",
    orgName: `Acme ${ts}`,
  });

  // Open the org's member directory.
  await page.getByRole("link", { name: "Users" }).click();
  await expect(page).toHaveURL(/\/admin\/users/);

  const memberEmail = `member-${ts}@example.com`;

  // Invite a member as a Service Owner.
  await page.getByRole("button", { name: "Invite user" }).click();
  await page.getByLabel("Email").fill(memberEmail);
  await page.getByLabel("Name (optional)").fill("Member One");
  await page.getByRole("button", { name: "Service Owner" }).click();
  await page.getByRole("button", { name: "Send invite" }).click();

  // They appear in the directory with the assigned role.
  await expect(page.getByRole("cell", { name: memberEmail })).toBeVisible();
  await expect(page.getByText("SERVICE_OWNER")).toBeVisible();

  // Add the Analyst role via the row's Roles submenu.
  await page.getByRole("button", { name: "User actions" }).click();
  await page.getByRole("menuitem", { name: "Roles" }).hover();
  await page.getByRole("menuitem", { name: "Analyst" }).click();
  await expect(page.getByText("ANALYST")).toBeVisible();

  // Remove them from the organization (the confirm is auto-accepted above).
  await page.getByRole("button", { name: "User actions" }).click();
  await page.getByRole("menuitem", { name: /remove from organization/i }).click();
  await expect(page.getByRole("cell", { name: memberEmail })).toBeHidden();
});

test("the workspace switcher creates and switches between organizations", async ({ page }) => {
  const ts = Date.now();
  const alpha = `Alpha ${ts}`;
  const beta = `Beta ${ts}`;

  await registerOrgAdmin(page, {
    name: "Switcher User",
    email: `switch-${ts}@example.com`,
    password: "password123",
    orgName: alpha,
  });

  // The switcher (sidebar header) shows the active org.
  await expect(page.getByRole("button", { name: new RegExp(alpha) })).toBeVisible();

  // Create a second org from the switcher; it becomes the active one.
  await page.getByRole("button", { name: new RegExp(alpha) }).click();
  await page.getByRole("menuitem", { name: /create organization/i }).click();
  await expect(page).toHaveURL(/\/organizations\/new/);
  await createOrg(page, beta);
  await expect(page.getByRole("button", { name: new RegExp(beta) })).toBeVisible();

  // Both orgs are listed, and switching back to Alpha changes the active workspace.
  await page.getByRole("button", { name: new RegExp(beta) }).click();
  await page.getByRole("menuitem", { name: new RegExp(alpha) }).click();
  await expect(page.getByRole("button", { name: new RegExp(alpha) })).toBeVisible();
});
