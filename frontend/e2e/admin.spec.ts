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
