import { expect, test, type Page } from "@playwright/test";
import type { BusinessService, Role, SbomImportResult, SbomImportSource } from "../src/lib/types";

const IMPORTED: SbomImportResult = {
  serviceId: "repository-service", componentsCreated: 3, componentsMatched: 1, serviceComponentsLinked: 4,
  serviceComponentsUnlinked: 0, dependenciesCreated: 2, dependenciesRemoved: 0, componentsSkipped: 0,
};
const REPOSITORY = "https://github.com/acme/payments";

type Options = { assigned?: boolean; linked?: boolean; source?: SbomImportSource; importFailure?: string };

// Browser contract checks use a fake BFF. Backend RBAC/tenancy and the clone itself are covered by the Java
// integration tests (ServiceRepositoryIntegrationTest, SbomRepositoryImportIntegrationTest).
async function fixture(page: Page, role: Role, { assigned = false, linked = false, source, importFailure = "" }: Options = {}) {
  let service: BusinessService = {
    id: "repository-service", name: "Repository demo", criticality: "HIGH", exposure: "INTERNAL",
    repositoryUrl: linked ? REPOSITORY : null,
    repositoryBranch: linked ? "develop" : null,
    sbomImportedAt: linked && source ? "2026-09-24T09:00:00Z" : null,
    sbomImportSource: linked && source ? source : null,
    createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-24T00:00:00Z",
  };
  const writes: { method: string; path: string; body: unknown }[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "demo-owner", email: "owner@example.test", displayName: "Demo owner",
        roles: [role], orgId: "demo-org", enabled: true, emailVerified: true, createdAt: service.createdAt,
      } });
    }
    if (path === "/api/actions/upgrade-recommendations") {
      return route.fulfill({ json: [] });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path.endsWith("/repository")) {
      writes.push({ method: request.method(), path, body: request.postDataJSON() });
      if (request.method() === "DELETE") {
        service = { ...service, repositoryUrl: null, repositoryBranch: null, sbomImportedAt: null, sbomImportSource: null };
        return route.fulfill({ status: 204 });
      }
      const body = request.postDataJSON();
      if (String(body.url).includes("not-allowed")) {
        return route.fulfill({ status: 400, json: { message: "This host is not allowed" } });
      }
      // Like the backend: stored canonical, and pointing elsewhere forgets the last import.
      const changed = service.repositoryUrl !== REPOSITORY || service.repositoryBranch !== body.branch;
      service = { ...service, repositoryUrl: REPOSITORY, repositoryBranch: body.branch,
        ...(changed ? { sbomImportedAt: null, sbomImportSource: null } : {}) };
      return route.fulfill({ json: service });
    }
    if (path.endsWith("/sbom/from-repository")) {
      const body = request.postDataJSON();
      writes.push({ method: "POST", path, body });
      if (importFailure && !body.token) {
        return route.fulfill({ status: 400, json: { message: importFailure } });
      }
      // A re-import unlinks what the repository no longer declares (an upgraded library's old version).
      const reimport = service.sbomImportedAt !== null;
      service = { ...service, sbomImportedAt: "2026-09-25T15:24:00Z", sbomImportSource: body.token ? "TOKEN" : "PUBLIC" };
      return route.fulfill({ json: reimport ? { ...IMPORTED, serviceComponentsUnlinked: 2 } : IMPORTED });
    }
    if (path === "/api/inventory/services/repository-service") {
      return route.fulfill({ json: service });
    }
    const content = path.endsWith("/owners") && assigned
      ? [{ id: "owner-row", userId: "demo-owner", name: "Demo owner", email: "owner@example.test" }] : [];
    return route.fulfill({ json: { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 } });
  });
  await page.goto("/inventory/services/repository-service");
  await expect(page.getByRole("heading", { name: "Repository demo" })).toBeVisible();
  return writes;
}

test("linking imports the SBOM at once; then one dialog re-imports, changes the branch or unlinks", async ({ page }) => {
  const writes = await fixture(page, "ANALYST");
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(page.getByRole("button", { name: "Upload CycloneDX JSON" })).toHaveCount(0);

  await section.getByLabel("Repository URL").fill("git@github.com:acme/payments.git");
  await section.getByLabel("Branch (optional)").fill("develop");
  await section.getByRole("button", { name: "Link & import SBOM" }).click();
  await expect(page.getByText("SBOM imported — 3 new, 1 matched, 4 linked, 2 dependencies.")).toBeVisible();
  await expect(section.getByText(/SBOM last imported .* · public repository/)).toBeVisible();
  const link = section.getByRole("link", { name: REPOSITORY });
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noopener noreferrer");
  expect(writes.slice(0, 2)).toEqual([
    { method: "PUT", path: "/api/inventory/services/repository-service/repository",
      body: { url: "git@github.com:acme/payments.git", branch: "develop" } },
    { method: "POST", path: "/api/inventory/services/repository-service/sbom/from-repository", body: {} },
  ]);
  // One way in: no separate "Change repository" or "Unlink" on the card.
  await expect(section.getByRole("button")).toHaveCount(1);

  // Straight after linking, the dialog shows the saved repository, ready to import again with Enter.
  await section.getByRole("button", { name: "Import SBOM" }).click();
  const dialog = page.getByRole("dialog", { name: "Import SBOM" });
  await expect(dialog.getByLabel("Repository URL")).toHaveValue(REPOSITORY);
  await expect(dialog.getByLabel("Branch (optional)")).toHaveValue("develop");
  await expect(dialog.getByRole("button", { name: "Private repository? Add an access token" })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Import SBOM", exact: true })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(dialog).toBeHidden();
  expect(writes[2]).toEqual({ method: "POST", path: "/api/inventory/services/repository-service/sbom/from-repository", body: {} });

  await section.getByRole("button", { name: "Import SBOM" }).click();
  await dialog.getByLabel("Branch (optional)").fill("");
  await dialog.getByRole("button", { name: "Save & import SBOM" }).click();
  await expect(dialog).toBeHidden();
  await expect(section.getByText("Repository default branch")).toBeVisible();
  expect(writes.slice(3, 5)).toEqual([
    { method: "PUT", path: "/api/inventory/services/repository-service/repository", body: { url: REPOSITORY, branch: null } },
    { method: "POST", path: "/api/inventory/services/repository-service/sbom/from-repository", body: {} },
  ]);

  await section.getByRole("button", { name: "Import SBOM" }).click();
  await dialog.getByRole("button", { name: "Unlink repository" }).click();
  await expect(dialog).toBeHidden();
  await expect(section.getByLabel("Repository URL")).toHaveValue("");
  expect(writes[5].method).toBe("DELETE");
});

test("a repository that needed a token last time asks for it before importing, not after failing", async ({ page }) => {
  const writes = await fixture(page, "ANALYST", { linked: true, source: "TOKEN" });
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(section.getByText(/SBOM last imported .* · with an access token/)).toBeVisible();

  await section.getByRole("button", { name: "Import SBOM" }).click();
  const dialog = page.getByRole("dialog", { name: "Import SBOM" });
  await expect(dialog.getByText("This repository needed an access token last time.")).toBeVisible();
  const token = dialog.getByLabel("Access token", { exact: true });
  await expect(token).toBeFocused();
  await token.fill("glpat-readonly");
  await dialog.getByRole("button", { name: "Import SBOM", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(
    page.getByText("SBOM imported — 3 new, 1 matched, 4 linked, 2 unlinked (no longer in the repository), 2 dependencies."),
  ).toBeVisible();
  expect(writes).toEqual([
    { method: "POST", path: "/api/inventory/services/repository-service/sbom/from-repository", body: { token: "glpat-readonly" } },
  ]);
});

test("a failed import stays in the dialog with the reason, and a token can be added there", async ({ page }) => {
  const failure = "https://github.com/acme/payments is private or doesn't exist. Add a read-only access token to import it";
  const writes = await fixture(page, "ANALYST", { linked: true, source: "PUBLIC", importFailure: failure });
  const section = page.getByRole("region", { name: "Service repository" });

  await section.getByRole("button", { name: "Import SBOM" }).click();
  const dialog = page.getByRole("dialog", { name: "Import SBOM" });
  await dialog.getByRole("button", { name: "Import SBOM", exact: true }).click();
  await expect(dialog.getByRole("alert")).toHaveText(failure);
  await dialog.getByLabel("Access token", { exact: true }).fill("ghp_readonly");
  await dialog.getByLabel("Username (optional)").fill("jdoe");
  await dialog.getByRole("button", { name: "Import SBOM", exact: true }).click();

  await expect(dialog).toBeHidden();
  await expect(section.getByText(/SBOM last imported .* · with an access token/)).toBeVisible();
  expect(writes.map((write) => write.body)).toEqual([{}, { token: "ghp_readonly", username: "jdoe" }]);
});

test("when the first import fails, the dialog opens with the reason and the saved repository", async ({ page }) => {
  const failure = "https://github.com/acme/payments is private or doesn't exist. Add a read-only access token to import it";
  await fixture(page, "COMPLIANCE_ADMIN", { importFailure: failure });
  const section = page.getByRole("region", { name: "Service repository" });

  await section.getByLabel("Repository URL").fill("https://github.com/acme/payments");
  await section.getByRole("button", { name: "Link & import SBOM" }).click();

  const dialog = page.getByRole("dialog", { name: "Import SBOM" });
  await expect(dialog.getByRole("alert")).toHaveText(failure);
  await expect(dialog.getByLabel("Repository URL")).toHaveValue(REPOSITORY);
  await expect(dialog.getByLabel("Access token", { exact: true })).toBeFocused();
  // The page behind the modal is hidden from assistive technology until it closes.
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(section.getByRole("link", { name: REPOSITORY })).toBeVisible();
  await expect(section.getByText("No SBOM imported from this repository yet.")).toBeVisible();
});

test("a link copied from a branch page fills in the branch, and a token can be given up front", async ({ page }) => {
  const writes = await fixture(page, "COMPLIANCE_ADMIN");
  const section = page.getByRole("region", { name: "Service repository" });

  await section.getByLabel("Repository URL").fill("https://github.com/acme/payments/tree/release-2");
  await expect(section.getByLabel("Branch (optional)")).toHaveValue("release-2");
  await section.getByRole("button", { name: "Private repository? Add an access token" }).click();
  await section.getByLabel("Access token", { exact: true }).fill("ghp_readonly");
  await section.getByRole("button", { name: "Link & import SBOM" }).click();

  await expect(page.getByText("SBOM imported — 3 new", { exact: false })).toBeVisible();
  expect(writes[0].body).toEqual({ url: "https://github.com/acme/payments/tree/release-2", branch: "release-2" });
  expect(writes[1].body).toEqual({ token: "ghp_readonly" });
});

test("a failed change keeps the dialog open with what was typed and imports nothing", async ({ page }) => {
  const writes = await fixture(page, "COMPLIANCE_ADMIN", { linked: true, source: "PUBLIC" });
  const section = page.getByRole("region", { name: "Service repository" });
  await section.getByRole("button", { name: "Import SBOM" }).click();
  const dialog = page.getByRole("dialog", { name: "Import SBOM" });
  await dialog.getByLabel("Repository URL").fill("https://not-allowed.example/acme/repo");
  await dialog.getByRole("button", { name: "Save & import SBOM" }).click();

  await expect(dialog.getByRole("alert")).toHaveText("This host is not allowed");
  await expect(dialog.getByLabel("Repository URL")).toHaveValue("https://not-allowed.example/acme/repo");
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(section.getByRole("link")).toHaveAttribute("href", REPOSITORY);
  expect(writes.filter((write) => write.path.endsWith("/sbom/from-repository"))).toHaveLength(0);
});

test("an assigned service owner can import", async ({ page }) => {
  await fixture(page, "SERVICE_OWNER", { assigned: true, linked: true, source: "PUBLIC" });
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(section.getByRole("button", { name: "Import SBOM" })).toBeVisible();
});

test("an unassigned service owner sees the repository and its import status, without controls", async ({ page }) => {
  await fixture(page, "SERVICE_OWNER", { linked: true, source: "INTEGRATION" });
  const section = page.getByRole("region", { name: "Service repository" });
  await expect(section.getByRole("link")).toBeVisible();
  await expect(section.getByText(/SBOM last imported .* · through the Aegis GitHub App/)).toBeVisible();
  await expect(section.getByRole("button")).toHaveCount(0);
});
