import { defineConfig } from "@playwright/test";

/**
 * End-to-end tests run against a real Chromium, so they exercise the app exactly as a user does
 * (trusted clicks, the Base UI menus, the server-side guard, the httpOnly-cookie BFF).
 *
 * Prerequisites: the identity-service backend on :8081 (2FA on, the default) with the
 * bootstrap admin seeded (admin@example.com / admin-dev-password), and Mailhog reachable at
 * http://localhost:8025 so the suite can read the emailed login codes. The frontend dev
 * server is started/reused automatically via `webServer` below. See the README "Testing".
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: "http://localhost:3000",
    browserName: "chromium",
    viewport: { width: 1280, height: 800 },
    navigationTimeout: 30_000,
    actionTimeout: 15_000,
    trace: "on-first-retry",
  },
  webServer: {
    command: "pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
