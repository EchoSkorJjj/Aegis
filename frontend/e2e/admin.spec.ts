import { test, expect } from "@playwright/test";

import { signIn } from "./helpers";

// Uses the seeded bootstrap admin (admin@example.com / admin-dev-password).
test("an admin can browse and search the user directory", async ({ page }) => {
  await signIn(page, "admin@example.com", "admin-dev-password");

  // The admin-only "Users" link appears in the sidebar and opens the directory.
  await page.getByRole("link", { name: "Users" }).click();
  await expect(page).toHaveURL(/\/admin\/users/);
  await expect(page.getByRole("columnheader", { name: /email/i })).toBeVisible();

  // Search brings the admin's own row into view (server-side filter)…
  await page.getByPlaceholder("Email or name…").fill("admin@example.com");
  await expect(page.getByRole("cell", { name: "admin@example.com" })).toBeVisible();

  // …and a term nobody matches shows the empty state.
  await page.getByPlaceholder("Email or name…").fill("zzz-no-such-user-zzz");
  await expect(page.getByText(/no users match/i)).toBeVisible();
});

test("a non-admin user does not see the admin area", async ({ page }) => {
  const email = `e2e-user-${Date.now()}@example.com`;
  const password = "password123";

  // A freshly registered account is a regular USER (no admin role).
  await page.goto("/signup");
  await page.getByLabel("Name").fill("Regular User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/login/);

  await signIn(page, email, password);

  // The app shell has loaded (their account menu is present)…
  await expect(page.getByRole("button", { name: /Regular User/ })).toBeVisible();
  // …but the admin-only "Users" link is not offered to a regular user.
  await expect(page.getByRole("link", { name: "Users" })).toBeHidden();
});
