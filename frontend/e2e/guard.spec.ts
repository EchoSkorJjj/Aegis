import { test, expect } from "@playwright/test";

test("visiting a protected page while logged out redirects to login", async ({ page }) => {
  await page.goto("/dashboard");

  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByLabel("Email")).toBeVisible();
});

test("the marketing home page is public", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL("http://localhost:3000/");
});

test("visiting the admin area while logged out redirects to login", async ({ page }) => {
  await page.goto("/admin/users");

  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByLabel("Email")).toBeVisible();
});
