import { test, expect } from "@playwright/test";

import { signIn } from "./helpers";

test("a visitor can sign up, sign in (with email OTP), and sign out", async ({ page }) => {
  const email = `e2e-${Date.now()}@example.com`;
  const password = "password123";

  // Sign up.
  await page.goto("/signup");
  await page.getByLabel("Name").fill("E2E User");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByLabel("Confirm password").fill(password);
  await page.getByRole("button", { name: "Create account" }).click();

  // Registration sends the visitor to the login page.
  await expect(page).toHaveURL(/\/login/);

  // Sign in — completes the emailed one-time code, then lands on the app shell.
  await signIn(page, email, password);
  await expect(page.getByText(/your workspace content goes here/i)).toBeVisible();

  // Sign out from the sidebar account menu.
  await page.getByRole("button", { name: /E2E User/ }).click();
  await page.getByRole("menuitem", { name: /log out/i }).click();
  await expect(page).toHaveURL(/\/login/);
});

test("invalid credentials are rejected", async ({ page }) => {
  // A throwaway email so this negative test never locks a real account (e.g. the shared bootstrap
  // admin) across runs — identity returns 401 for unknown users and wrong passwords alike.
  await page.goto("/login");
  await page.getByLabel("Email").fill(`nobody-${Date.now()}@example.com`);
  await page.getByLabel("Password", { exact: true }).fill("definitely-not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  // An inline error shows, no second factor is issued, and we stay on the login page.
  await expect(page.getByText("Invalid email or password")).toBeVisible();
  await expect(page.getByPlaceholder("123456")).toBeHidden();
  await expect(page).toHaveURL(/\/login/);
});
