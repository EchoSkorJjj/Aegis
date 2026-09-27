import { expect, test, type Page } from "@playwright/test";
import type {
  BusinessService,
  Owner,
  PackageUsage,
  Role,
  SoftwareComponent,
  ThirdPartyVendor,
  VendorUsage,
} from "../src/lib/types";

// Browser contract checks use a fake BFF. The walk itself — each service's own dependency graph, every version of
// the package — is covered by the Java tests (UsedByServiceTest, ServiceDependencyGraphIntegrationTest).
const AT = "2026-09-27T00:00:00Z";

function component(id: string, name: string, version: string): SoftwareComponent {
  return { id, ecosystem: "npm", name, version, createdAt: AT, updatedAt: AT };
}

function service(id: string, name: string, criticality: BusinessService["criticality"]): BusinessService {
  return {
    id, name, criticality, exposure: "INTERNAL", repositoryUrl: null, repositoryBranch: null,
    sbomImportedAt: null, sbomImportSource: null, createdAt: AT, updatedAt: AT,
  };
}

const OLD = component("axios-150", "axios", "1.5.0");
const CURRENT = component("axios-1180", "axios", "1.18.0");
const LEFT_PAD = component("left-pad-130", "left-pad", "1.3.0");
const HEALTH = service("health", "HealthcareApp", "HIGH");
const BILLING = service("billing", "Billing", "CRITICAL");
const OWNER: Owner = { id: "owner-1", userId: "user-1", name: "Dana Lee", email: "dana@example.test", createdAt: AT, updatedAt: AT };
const CLOUD: ThirdPartyVendor = { id: "cloud", name: "Cloud", createdAt: AT, updatedAt: AT };

const AXIOS: PackageUsage = {
  ecosystem: "npm",
  name: "axios",
  uses: [
    {
      service: BILLING, owners: [], component: OLD, direct: false,
      through: [component("sdk", "payments-sdk", "4.0.0"), component("retry", "axios-retry", "3.0.0")],
    },
    { service: HEALTH, owners: [OWNER], component: OLD, direct: true, through: [] },
    { service: HEALTH, owners: [OWNER], component: CURRENT, direct: true, through: [] },
  ],
};

async function fixture(page: Page, role: Role) {
  const lookups: string[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "user-1", email: "dana@example.test", displayName: "Dana Lee",
        roles: [role], orgId: "demo-org", enabled: true, emailVerified: true, createdAt: AT,
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path === "/api/inventory/components/ecosystems") {
      return route.fulfill({ json: ["npm"] });
    }
    if (path === "/api/inventory/components/used-by") {
      lookups.push(url.search);
      const usage: PackageUsage = url.searchParams.get("name") === "axios"
        ? AXIOS : { ecosystem: "npm", name: url.searchParams.get("name") ?? "", uses: [] };
      return route.fulfill({ json: usage });
    }
    if (path === "/api/inventory/components") {
      return route.fulfill({ json: page_([OLD, CURRENT, LEFT_PAD]) });
    }
    if (path === "/api/inventory/vendors/cloud/used-by") {
      lookups.push(path);
      const usage: VendorUsage = { vendor: CLOUD, services: [{ service: BILLING, owners: [OWNER] }] };
      return route.fulfill({ json: usage });
    }
    if (path === "/api/inventory/vendors") {
      return route.fulfill({ json: page_([CLOUD]) });
    }
    return route.fulfill({ json: page_([]) });
  });
  return lookups;
}

function page_<T>(content: T[]) {
  return { content, totalElements: content.length, totalPages: 1, number: 0, size: 10 };
}

test("Used by on a component lists every version of its package, direct or through which component", async ({ page }) => {
  const lookups = await fixture(page, "ANALYST");
  await page.goto("/inventory/components");
  await expect(page.getByRole("heading", { name: "Components" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Dependency map" })).toHaveCount(0);

  const toggle = page.getByRole("button", { name: "Used by: axios 1.5.0" });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");

  await expect(page.getByText("2 services use axios, in 2 versions. Every version is listed; 1.5.0 is in bold."))
    .toBeVisible();
  const used = page.getByRole("table", { name: "Services using axios" });
  const rows = used.getByRole("row");
  await expect(rows).toHaveCount(4);
  await expect(rows.nth(1)).toContainText("Billing");
  await expect(rows.nth(1)).toContainText("1.5.0");
  await expect(rows.nth(1)).toContainText("Through payments-sdk 4.0.0 › axios-retry 3.0.0");
  await expect(rows.nth(1)).toContainText("No owners assigned");
  await expect(rows.nth(2)).toContainText("HealthcareApp");
  await expect(rows.nth(2)).toContainText("Direct");
  await expect(rows.nth(2)).toContainText("Dana Lee");
  await expect(rows.nth(3)).toContainText("1.18.0");
  await expect(used.getByRole("link", { name: "HealthcareApp" }).first()).toHaveAttribute(
    "href", "/inventory/services/health",
  );
  // (Dev mode's strict effects may ask twice; it is always the whole package.)
  expect([...new Set(lookups)]).toEqual(["?ecosystem=npm&name=axios"]);

  // One row's panel at a time; the same button closes it.
  await page.getByRole("button", { name: "Used by: left-pad 1.3.0" }).click();
  await expect(page.getByText("No service uses any version of left-pad.")).toBeVisible();
  await expect(used).toBeHidden();
  await page.getByRole("button", { name: "Used by: left-pad 1.3.0" }).click();
  await expect(page.getByText("No service uses any version of left-pad.")).toBeHidden();
});

test("a member who can only read the inventory can still see who uses a component", async ({ page }) => {
  await fixture(page, "SERVICE_OWNER");
  await page.goto("/inventory/components");

  await expect(page.getByRole("button", { name: "component actions" })).toHaveCount(0);
  await page.getByRole("button", { name: "Used by: axios 1.18.0" }).click();
  await expect(page.getByRole("table", { name: "Services using axios" })).toBeVisible();
});

test("Used by on a vendor lists the services using it", async ({ page }) => {
  const lookups = await fixture(page, "COMPLIANCE_ADMIN");
  await page.goto("/inventory/vendors");
  await expect(page.getByRole("heading", { name: "Vendors" })).toBeVisible();

  await page.getByRole("button", { name: "Used by: Cloud" }).click();

  await expect(page.getByText("1 service uses Cloud.")).toBeVisible();
  const used = page.getByRole("table", { name: "Services using Cloud" });
  await expect(used.getByRole("row").nth(1)).toContainText("Billing");
  await expect(used.getByRole("row").nth(1)).toContainText("CRITICAL");
  await expect(used.getByRole("row").nth(1)).toContainText("Direct");
  await expect(used.getByRole("row").nth(1)).toContainText("Dana Lee");
  expect([...new Set(lookups)]).toEqual(["/api/inventory/vendors/cloud/used-by"]);
});
