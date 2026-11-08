import { appendFileSync, readdirSync } from "node:fs";

import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

import { PR } from "./helpers/findings";
import recorded from "./fixtures/recorded-estate.json";

// CS203-131: every page works at phone width, every control is reachable by keyboard, and form fields have labels.
// The backend is faked with responses recorded from the real stack (fixtures/recorded-estate.json, with its names
// made long on purpose), so every page renders populated, without needing the stack.
type Who = "analyst" | "admin" | "owner" | null;
const { ids, responses } = recorded as unknown as { ids: Record<string, string>; responses: Record<string, unknown> };

const PAGES: { path: string; who: Who }[] = [
  { path: "/", who: null },
  { path: "/privacy", who: null },
  { path: "/login", who: null },
  { path: "/signup", who: null },
  { path: "/forgot-password", who: null },
  { path: "/reset-password?token=fixture", who: null },
  { path: "/accept-invite?token=fixture", who: null },
  { path: "/verify-email?token=fixture", who: null },
  { path: "/dashboard", who: "analyst" },
  { path: "/inventory/services", who: "analyst" },
  { path: `/inventory/services/${ids.service}`, who: "analyst" },
  { path: "/inventory/components", who: "analyst" },
  { path: "/inventory/vendors", who: "analyst" },
  { path: "/inventory/owners", who: "analyst" },
  { path: "/advisories", who: "analyst" },
  { path: `/advisories/${ids.signal}`, who: "analyst" },
  { path: "/findings", who: "analyst" },
  { path: "/findings/mine", who: "owner" },
  { path: `/findings/${ids.signal}`, who: "analyst" },
  { path: "/responses/review", who: "analyst" },
  { path: "/responses/history", who: "analyst" },
  { path: `/responses/tickets/${ids.ticket}`, who: "analyst" },
  { path: "/responses/tickets/proposed", who: "analyst" },
  { path: `/responses/upgrades/${PR.id}`, who: "analyst" },
  { path: `/responses/memos/${ids.memo}`, who: "analyst" },
  { path: "/reports/audit", who: "analyst" },
  { path: "/reports/evidence", who: "analyst" },
  { path: "/settings", who: "analyst" },
  { path: "/admin/users", who: "admin" },
  { path: "/admin/integrations", who: "admin" },
  { path: "/admin/integrations/jira/callback?error=access_denied", who: "admin" },
  { path: "/admin/integrations/github/callback?error=access_denied", who: "admin" },
  { path: "/organizations/new", who: "admin" },
];

const PHONE = { width: 360, height: 740 };
const DESKTOP = { width: 1280, height: 800 };

async function fakeBackend(page: Page, who: Who) {
  if (who) {
    await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  }
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === "/api/auth/config") return route.fulfill({ json: responses["/api/auth/config"] });
    if (url.pathname === "/api/auth/me" || url.pathname === "/api/orgs") {
      if (!who) return route.fulfill({ status: 401, json: { message: "Not signed in" } });
      const source = who === "admin" ? "admin" : "analyst";
      const body = responses[`${source}:${url.pathname}`] as Record<string, unknown> | Record<string, unknown>[];
      if (who !== "owner") return route.fulfill({ json: body });
      return route.fulfill({ json: Array.isArray(body)
        ? body.map((org) => ({ ...org, roles: ["SERVICE_OWNER"] })) : { ...body, roles: ["SERVICE_OWNER"] } });
    }
    if (url.pathname === `/api/actions/upgrade-proposals/${PR.id}`) {
      return route.fulfill({ json: { ...PR, riskTicketId: null, standalone: true } });
    }
    // The same response, still awaiting its decision: the page with every control on it.
    if (url.pathname === "/api/actions/risk-tickets/proposed") {
      return route.fulfill({ json: { ...(responses[`/api/actions/risk-tickets/${ids.ticket}`] as object), id: "proposed",
        status: "PROPOSED", stage: "IN_REVIEW", decidedBy: null, decidedAt: null, issueKey: null, issueUrl: null, approval: null } });
    }
    if (url.pathname === "/api/findings/my-services") return route.fulfill({ json: [] });
    const exact = url.pathname + (url.search ? `?${url.searchParams.toString()}` : "");
    const body = responses[exact] ?? responses[url.pathname];
    if (body !== undefined) return route.fulfill({ json: body });
    if (request.method() !== "GET") return route.fulfill({ json: {} });
    // A list nothing was recorded for: the same list under other filters, or empty.
    const similar = Object.keys(responses).find((key) => key.startsWith(`${url.pathname}?`));
    return route.fulfill({ json: similar ? responses[similar] : { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } });
  });
}

async function open(page: Page, path: string, who: Who) {
  await fakeBackend(page, who);
  await page.goto(path);
  await page.waitForLoadState("networkidle");
  await expect(page.locator("[data-slot=skeleton]")).toHaveCount(0);
  await expect(page.locator("body")).not.toBeEmpty();
}

/** Every serious WCAG A/AA failure axe can detect, which includes a field with no label and a nameless control. */
async function expectNoAxeViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .exclude("nextjs-portal")
    .analyze();
  const summary = violations.map((violation) => ({
    rule: violation.id,
    nodes: violation.nodes.slice(0, 4).map((node) => node.target.join(" ")),
  }));
  expect(summary).toEqual([]);
}

/** Tabs through the page and returns the visible, enabled controls that focus never reached. */
async function unreachableByKeyboard(page: Page): Promise<string[]> {
  const total = await page.evaluate(() => {
    const controls = [...document.querySelectorAll<HTMLElement>(
      "a[href], button, input, select, textarea, summary, [tabindex]:not([tabindex='-1'])",
    )].filter((el) => !el.closest("nextjs-portal") && !(el as HTMLButtonElement).disabled
      && (el as HTMLInputElement).type !== "hidden" && el.checkVisibility({ checkVisibilityCSS: true }) && el.getClientRects().length > 0);
    controls.forEach((el, index) => el.setAttribute("data-kb", String(index)));
    (window as unknown as { kbSeen: Set<string> }).kbSeen = new Set();
    document.addEventListener("focusin", (event) => {
      const id = (event.target as HTMLElement).getAttribute?.("data-kb");
      if (id) (window as unknown as { kbSeen: Set<string> }).kbSeen.add(id);
    });
    return controls.length;
  });
  for (let i = 0; i < total + 20; i++) await page.keyboard.press("Tab");
  return page.evaluate(() => {
    const seen = (window as unknown as { kbSeen: Set<string> }).kbSeen;
    return [...document.querySelectorAll<HTMLElement>("[data-kb]")]
      .filter((el) => !seen.has(el.getAttribute("data-kb")!))
      .map((el) => el.outerHTML.slice(0, 120));
  });
}

test("every page in the app is in the list above", () => {
  const routes = readdirSync("src/app", { recursive: true, encoding: "utf8" })
    .map((file) => `/${file.replaceAll("\\", "/")}`)
    .filter((file) => file.endsWith("/page.tsx"))
    .map((file) => file.replace(/\/page\.tsx$/, "").replace(/\/\([^/)]+\)/g, "") || "/");
  expect(routes.length).toBeGreaterThan(0);
  const listed = PAGES.map(({ path }) => path.split("?")[0]);
  const missing = routes.filter((route) => {
    const pattern = new RegExp(`^${route.replace(/\[[^\]]+\]/g, "[^/]+")}$`);
    return !listed.some((path) => pattern.test(path));
  });

  // The frontend's coverage target (testing/test-strategy.md): every route opened by a test.
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `### Coverage: frontend\n\n${routes.length - missing.length} of `
      + `${routes.length} routes opened by the accessibility spec (target: all).\n`);
  }
  expect(missing).toEqual([]);
});

for (const { path, who } of PAGES) {
  test.describe(path, () => {
    // axe and tabbing through every control, on a dev server compiling the page for the first time.
    test.describe.configure({ timeout: 120_000 });

    test("at phone width: nothing runs off the screen, and no WCAG failures", async ({ page }) => {
      await page.setViewportSize(PHONE);
      await open(page, path, who);
      const overflow = await page.evaluate(() => {
        const width = document.documentElement.clientWidth;
        return {
          scrollWidth: document.documentElement.scrollWidth,
          width,
          // Inside a scrolling table is fine; anywhere else, this element is cut off.
          past: [...document.querySelectorAll<HTMLElement>("main *, body > div *")]
            .filter((el) => el.getBoundingClientRect().right > width + 1 && !el.closest("[data-slot=table-container], .overflow-x-auto, [aria-hidden=true], nextjs-portal, .sr-only")
              && getComputedStyle(el).position !== "fixed" && el.getClientRects().length > 0)
            .slice(0, 5).map((el) => el.outerHTML.slice(0, 100)),
        };
      });
      expect(overflow.past).toEqual([]);
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.width);
      await expectNoAxeViolations(page);
    });

    test("every control is reachable by keyboard", async ({ page }) => {
      await page.setViewportSize(DESKTOP);
      await open(page, path, who);
      await expectNoAxeViolations(page);
      expect(await unreachableByKeyboard(page)).toEqual([]);
    });
  });
}

test.describe("keyboard journeys", () => {
  test("the first Tab offers a skip link to the page's content", async ({ page }) => {
    await open(page, "/inventory/services", "analyst");
    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Skip to main content" });
    await expect(skip).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.locator("#main-content")).toBeFocused();
  });

  test("on a phone, the sidebar opens from the keyboard and closes after following a link", async ({ page }) => {
    await page.setViewportSize(PHONE);
    await open(page, "/dashboard", "analyst");
    await page.getByRole("button", { name: "Toggle Sidebar" }).focus();
    await page.keyboard.press("Enter");
    const settings = page.getByRole("link", { name: "Settings" });
    await expect(settings).toBeVisible();
    await settings.focus();
    await page.keyboard.press("Enter");
    await page.waitForURL(/\/settings/);
    await expect(settings).toBeHidden();
  });

  test("the link picker is a combobox: arrows move, Enter links, Escape closes", async ({ page }) => {
    const linked: string[] = [];
    await open(page, `/inventory/services/${ids.service}`, "analyst");
    await page.route(`**/api/inventory/services/${ids.service}/components/**`, async (route) => {
      if (route.request().method() !== "POST" && route.request().method() !== "PUT") return route.fallback();
      linked.push(route.request().url());
      return route.fulfill({ json: {} });
    });
    const picker = page.getByRole("combobox", { name: "Link an existing component…" });
    await picker.focus();
    const options = page.getByRole("listbox").getByRole("option");
    await expect(options.first()).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(options.first()).toBeHidden();
    await page.keyboard.press("ArrowDown");
    await expect(options.first()).toBeVisible();
    await expect(options.first()).toHaveAttribute("aria-selected", "true");
    await expect(picker).toHaveAttribute("aria-activedescendant", (await options.first().getAttribute("id"))!);
    await page.keyboard.press("Enter");
    await expect.poll(() => linked.length).toBe(1);
  });

  test("a response's approval stays on a phone's screen and works from the keyboard", async ({ page }) => {
    await page.setViewportSize(PHONE);
    await open(page, "/responses/review", "analyst");
    await page.goto(`/responses/upgrades/${PR.id}`);
    await page.waitForLoadState("networkidle");
    const dismiss = page.getByRole("button", { name: /^Dismiss/ }).first();
    await dismiss.focus();
    await page.keyboard.press("Enter");
    const popup = page.locator("[data-slot=popover-content]");
    await expect(popup).toBeVisible();
    const box = (await popup.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(PHONE.width);
    await expect(popup.getByRole("textbox")).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(popup).toBeHidden();
    await expect(dismiss).toBeFocused();
  });
});
