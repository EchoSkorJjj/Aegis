import { defineConfig } from "@playwright/test";

/**
 * The journeys against the real backend, kept apart from the default suite because they need it running:
 * `e2e-full-stack/stack.sh up`, then `pnpm exec playwright test --config playwright.full-stack.config.ts`. On port 3100, so a frontend already on :3000 (the
 * Docker image, usually an older build) is never tested by mistake.
 */
// The journey reads its emailed sign-in codes from the stack's own Mailhog.
process.env.MAILHOG_API ??= "http://localhost:58025";

export default defineConfig({
  testDir: "./e2e-full-stack",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]] : "list",
  timeout: 420_000,
  expect: { timeout: 30_000 },
  // Generous because the dev server compiles each page the first time it is opened.
  use: {
    baseURL: "http://localhost:3100",
    browserName: "chromium",
    viewport: { width: 1280, height: 800 },
    navigationTimeout: 60_000,
    actionTimeout: 60_000,
    trace: "retain-on-failure",
  },
  webServer: {
    command: "pnpm dev --port 3100",
    url: "http://localhost:3100",
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    env: { GATEWAY_URL: "http://localhost:8080", AUTH_COOKIE_SECURE: "false" },
  },
});
