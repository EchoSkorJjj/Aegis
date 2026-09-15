import { test, expect } from "@playwright/test";

import { signIn } from "./helpers";

// The seeded bootstrap admin (admin@example.com / admin-dev-password) is a PLATFORM_ADMIN that
// belongs to no organization. It's the ONLY test that signs in as this shared account — keep it
// that way: two suites logging in as the same MFA account concurrently race on its emailed codes.
test("a platform admin browses users and manages identities, with no org-role controls", async ({
  page,
}) => {
  const ts = Date.now();
  await signIn(page, "admin@example.com", "admin-dev-password");

  // The admin "Users" link opens the directory (a platform admin sees every identity).
  await page.getByRole("link", { name: "Users" }).click();
  await expect(page).toHaveURL(/\/admin\/users/);
  await expect(page.getByRole("columnheader", { name: /email/i })).toBeVisible();

  // A platform admin's invite form onboards an account with NO org-role picker.
  await page.getByRole("button", { name: "Invite user" }).click();
  await expect(page.getByRole("button", { name: "Service Owner" })).toBeHidden();
  const inviteEmail = `platform-invite-${ts}@example.com`;
  await page.getByLabel("Email").fill(inviteEmail);
  await page.getByLabel("Name (optional)").fill("Platform Invitee");
  await page.getByRole("button", { name: "Send invite" }).click();

  // A term nobody matches shows the empty state (server-side search).
  await page.getByPlaceholder("Email or name…").fill("zzz-no-such-user-zzz");
  await expect(page.getByText(/no users match/i)).toBeVisible();

  // Narrow to the invited user; its row offers platform controls only — promote to platform admin,
  // but no org "Roles" submenu.
  await page.getByPlaceholder("Email or name…").fill(inviteEmail);
  const row = page.getByRole("row", { name: new RegExp(inviteEmail) });
  await expect(row.getByRole("cell", { name: inviteEmail })).toBeVisible();
  await row.getByRole("button", { name: "User actions" }).click();
  await expect(page.getByRole("menuitem", { name: /make platform admin/i })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Roles" })).toBeHidden();
});

test("a brand-new account is guided to create or join an organization", async ({ page }) => {
  const email = `e2e-user-${Date.now()}@example.com`;
  const password = "password123";

  // A freshly registered account is a regular USER that belongs to no organization yet.
  await page.goto("/signup");
  await page.getByLabel("Name").fill("Regular User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/login/);

  await signIn(page, email, password);

  // It lands on the create-or-join screen — no app sidebar, so no admin area is reachable (ADR 0001).
  await expect(page.getByText(/create or join an organization/i)).toBeVisible();
  await expect(page.getByRole("link", { name: "Users" })).toBeHidden();
});
