import { readFile } from "node:fs/promises";

import { expect, test, type Page } from "@playwright/test";
import type { DecisionRecord, Role } from "../src/lib/types";

// Browser contract checks with a fake BFF: the Evidence page previews a service's period, asks for the export with
// the service, the period as whole local days and the reader's time zone, and saves the file under its own name.
// What an export holds, and that it matches the trail, is EvidenceExportIntegrationTest's (aegis-core).
const PAYMENTS = { id: "service-payments", name: "Payments API" };
const RETIRED = { id: "service-retired", name: "Retired billing" };
const DIGEST = "3f".repeat(32);

function record(sequence: number, overrides: Partial<DecisionRecord> = {}): DecisionRecord {
  return {
    id: `record-${sequence}`, sequence, occurredAt: "2026-10-09T02:00:00Z", subject: "RISK_TICKET",
    subjectId: "ticket-1", responseId: "ticket-1", serviceId: PAYMENTS.id, serviceName: PAYMENTS.name,
    serviceIds: [PAYMENTS.id], changeSignalId: "signal-1", changeTitle: "CVE-2021-44228 in log4j-core 2.14.1",
    action: "APPROVED", actorId: "user-bob", reason: null, score: 91, tier: "CRITICAL", ...overrides,
  };
}

async function fakeBff(page: Page, role: Role, exportStatus = 200) {
  const exports: URLSearchParams[] = [];
  const searches: URLSearchParams[] = [];
  await page.context().addCookies([{ name: "um_refresh", value: "browser-fixture", url: "http://localhost:3000" }]);
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === "/api/auth/me") {
      return route.fulfill({ json: {
        id: "me", email: "me@example.test", displayName: "Me", roles: [role], orgId: "demo-org",
        enabled: true, emailVerified: true, createdAt: "2026-09-01T00:00:00Z",
      } });
    }
    if (path === "/api/orgs") {
      return route.fulfill({ json: [{ id: "demo-org", name: "Demo org", roles: [role], status: "ACTIVE" }] });
    }
    if (path === "/api/inventory/services") {
      return route.fulfill({ json: { content: [PAYMENTS], totalElements: 1, totalPages: 1, number: 0, size: 50 } });
    }
    if (path === "/api/audit/decisions/removed-services") {
      return route.fulfill({ json: [RETIRED] });
    }
    if (path === "/api/audit/decisions") {
      searches.push(url.searchParams);
      const content = [record(12, { action: "DISMISSED", reason: "Accepted risk" }), record(11), record(10)];
      return route.fulfill({ json: { content, totalElements: 7, totalPages: 3, number: 0, size: 5 } });
    }
    if (path === "/api/audit/evidence") {
      exports.push(url.searchParams);
      if (exportStatus !== 200) {
        const message = exportStatus === 502 ? "Could not list the organization's members with identity-service"
          : "This period holds 6000 decision records, more than one CSV export takes (5000): export a shorter period";
        return route.fulfill({ status: exportStatus, json: { message } });
      }
      const pdf = url.searchParams.get("format") === "pdf";
      return route.fulfill({
        status: 200,
        body: pdf ? "%PDF-1.7 fake evidence" : "﻿\"record_id\"\r\n",
        headers: {
          "content-type": pdf ? "application/pdf" : "text/csv;charset=UTF-8",
          "content-disposition": `attachment; filename="aegis-evidence-payments-api-2026-07-14-to-2026-10-11.${pdf ? "pdf" : "csv"}"`,
          "x-evidence-csv-sha256": DIGEST,
          "x-evidence-records": "7",
        },
      });
    }
    return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20 } });
  });
  return { exports, searches };
}

test("a compliance admin previews a service's period and downloads its evidence as PDF and CSV", async ({ page }) => {
  const { exports, searches } = await fakeBff(page, "COMPLIANCE_ADMIN");
  await page.goto("/reports/evidence");
  await expect(page.getByRole("heading", { name: "Evidence", exact: true })).toBeVisible();
  const download = page.getByRole("button", { name: "Download PDF" });
  await expect(download).toBeDisabled();
  await expect(page.getByText("Choose a service to see what the export holds.")).toBeVisible();

  await page.getByLabel("Service", { exact: true }).selectOption(PAYMENTS.id);
  const preview = page.getByRole("region", { name: "What the export holds" });
  await expect(preview).toContainText("7 decision records in this period");
  await expect(preview).toContainText("Dismissed");
  await expect(preview).toContainText("and 4 earlier, all in the export");
  const search = searches.at(-1)!;
  expect(search.get("serviceId")).toBe(PAYMENTS.id);
  // The last 90 days by default, as whole days: the period ends at the start of tomorrow.
  const from = new Date(search.get("from")!);
  const to = new Date(search.get("to")!);
  expect(Math.round((to.getTime() - from.getTime()) / 86_400_000)).toBe(90);
  expect(to.getHours()).toBe(0);
  expect(to.getTime()).toBeGreaterThan(Date.now());

  const pdf = page.waitForEvent("download");
  await download.click();
  const file = await pdf;
  expect(file.suggestedFilename()).toBe("aegis-evidence-payments-api-2026-07-14-to-2026-10-11.pdf");
  expect((await readFile(await file.path())).toString()).toBe("%PDF-1.7 fake evidence");
  const asked = exports.at(-1)!;
  expect(asked.get("format")).toBe("pdf");
  expect(asked.get("serviceId")).toBe(PAYMENTS.id);
  expect(asked.get("from")).toBe(search.get("from"));
  expect(asked.get("to")).toBe(search.get("to"));
  expect(asked.get("zone")).toBe(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone));
  const saved = page.getByRole("status");
  await expect(saved).toContainText("Saved aegis-evidence-payments-api-2026-07-14-to-2026-10-11.pdf: 7 decision records.");
  await expect(saved).toContainText(DIGEST);
  // The digest is of the CSV the PDF carries; a CSV downloaded on its own is said to be another reading.
  await expect(saved).toContainText("The CSV of these records is attached inside the PDF");
  await expect(saved).toContainText("A CSV downloaded on its own is another reading");
  await page.screenshot({ path: test.info().outputPath("evidence.png"), fullPage: true });

  const csv = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download CSV" }).click();
  expect((await csv).suggestedFilename()).toMatch(/\.csv$/);
  expect(exports.at(-1)!.get("format")).toBe("csv");
  await expect(saved).toContainText(`SHA-256 of this file: ${DIGEST}`);
  // What the CSV keeps is said as it is.
  await expect(page.getByText("The CSV keeps every field as recorded, except that a field beginning with", { exact: false }))
    .toBeVisible();
});

test("a service removed from the inventory can still be exported", async ({ page }) => {
  const { exports } = await fakeBff(page, "COMPLIANCE_ADMIN");
  await page.goto("/reports/evidence");
  const picker = page.getByLabel("Service", { exact: true });
  await expect(picker.locator("optgroup[label='No longer in the inventory'] option")).toHaveText([
    "Retired billing (removed)",
  ]);
  await picker.selectOption({ label: "Retired billing (removed)" });

  const csv = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download CSV" }).click();
  await csv;
  expect(exports.at(-1)!.get("serviceId")).toBe(RETIRED.id);
});

test("an export the backend refuses says why", async ({ page }) => {
  await fakeBff(page, "ANALYST", 400);
  await page.goto("/reports/evidence");
  await page.getByLabel("Service", { exact: true }).selectOption(PAYMENTS.id);
  await page.getByRole("button", { name: "Download CSV" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "export a shorter period" }))
    .toContainText("more than one CSV export takes (5000)");
  await expect(page.getByRole("status")).toHaveCount(0);
});

test("an export made while the member directory is unreachable is refused, and said so plainly", async ({ page }) => {
  await fakeBff(page, "ANALYST", 502);
  await page.goto("/reports/evidence");
  await page.getByLabel("Service", { exact: true }).selectOption(PAYMENTS.id);
  await page.getByRole("button", { name: "Download PDF" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "member directory" }))
    .toContainText("couldn't reach the member directory to name the people in the export, so it made none");
  await expect(page.getByRole("status")).toHaveCount(0);
});

test("a service owner has no evidence export", async ({ page }) => {
  await fakeBff(page, "SERVICE_OWNER");
  await page.goto("/reports/evidence");
  await expect(page.getByText("You don't have access to evidence exports", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download PDF" })).toHaveCount(0);
});
