import { expect, test, type Page } from "@playwright/test";
import type { OwnerNotification, Role } from "../src/lib/types";

// Browser contract checks with a fake BFF. Who is notified, once, and the email through identity-service are
// covered by OwnerNotificationIntegrationTest (aegis-core) and InternalApiIntegrationTest (identity-service).
function notification(overrides: Partial<OwnerNotification> = {}): OwnerNotification {
  return {
    id: "notification-1", userId: "me", serviceId: "service-1", changeSignalId: "signal-1",
    itemKind: "VULNERABILITY", title: "CVE-2021-44228 in log4j-core 2.14.1", serviceName: "Payments API",
    score: 91, tier: "CRITICAL", createdAt: "2026-10-09T02:00:00Z", readAt: null, emailStatus: "SENT",
    emailedAt: "2026-10-09T02:00:05Z", ...overrides,
  };
}

async function fakeBff(page: Page, role: Role, notifications: OwnerNotification[]) {
  const store = notifications.map((item) => ({ ...item }));
  const calls: string[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org",
        enabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z",
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path.startsWith("/api/notifications")) {
      calls.push(`${request.method()} ${path}`);
      const unread = () => store.filter((item) => item.readAt === null).length;
      if (path === "/api/notifications/unread-count") return route.fulfill({ json: { unread: unread() } });
      if (path === "/api/notifications/read-all") {
        const marked = unread();
        store.forEach((item) => { item.readAt = item.readAt ?? "2026-10-09T03:00:00Z"; });
        return route.fulfill({ json: { marked } });
      }
      const read = path.match(/^\/api\/notifications\/([^/]+)\/read$/);
      if (read) {
        const item = store.find((candidate) => candidate.id === read[1]);
        if (item) item.readAt = "2026-10-09T03:00:00Z";
        return route.fulfill({ status: 204, body: "" });
      }
      return route.fulfill({ json: { content: store, totalElements: store.length, totalPages: 1, number: 0, size: 10 } });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { calls };
}

test("an owner sees how many items need their decision, and opening one marks it read and goes to it", async ({ page }) => {
  const { calls } = await fakeBff(page, "SERVICE_OWNER", [
    notification(),
    notification({ id: "notification-2", changeSignalId: "signal-2", itemKind: "VENDOR_OUTAGE",
      title: "API outage at Acme Pay", score: 60, tier: "MEDIUM" }),
  ]);
  await page.goto("/dashboard");

  const bell = page.getByRole("button", { name: "Notifications, 2 unread" });
  await expect(bell).toBeVisible();
  await bell.click();
  const panel = page.getByRole("region", { name: "Notifications" });
  await expect(panel.getByText("CVE-2021-44228 in log4j-core 2.14.1")).toBeVisible();
  await expect(panel.getByText("Vendor outage in Payments API", { exact: false })).toBeVisible();
  await expect(panel.getByLabel("Unread")).toHaveCount(2);

  await panel.getByRole("button", { name: /API outage at Acme Pay/ }).click();
  await expect(page).toHaveURL(/\/findings\/signal-2$/);
  expect(calls).toContain("POST /api/notifications/notification-2/read");
  // The item's page is the item detail spec's; back where we were, one is left unread.
  await page.goto("/dashboard");
  await expect(page.getByRole("button", { name: "Notifications, 1 unread" })).toBeVisible();
});

test("marking all read clears the count", async ({ page }) => {
  const { calls } = await fakeBff(page, "SERVICE_OWNER", [notification()]);
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "Notifications, 1 unread" }).click();
  await page.getByRole("button", { name: "Mark all read" }).click();

  await expect(page.getByRole("button", { name: "Notifications", exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Notifications" }).getByLabel("Unread")).toHaveCount(0);
  expect(calls).toContain("POST /api/notifications/read-all");
});

test("with nothing yet, the bell says what will appear there", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER", []);
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "Notifications", exact: true }).click();
  await expect(page.getByText("When an item in a service you own needs your decision, it appears here", { exact: false }))
    .toBeVisible();
});

test("someone without a domain role has no bell and never asks for notifications", async ({ page }) => {
  const { calls } = await fakeBff(page, "USER", [notification()]);
  await page.goto("/dashboard");
  await expect(page.getByRole("button", { name: "Toggle Sidebar" }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /Notifications/ })).toHaveCount(0);
  expect(calls).toEqual([]);
});
