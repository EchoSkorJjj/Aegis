import { expect, test } from "@playwright/test";

// The page Atlassian links to when someone grants Aegis access to Jira: it has to open without an Aegis
// account. The contact line reads PRIVACY_CONTACT at request time; unset here, so the fallback shows.
test("the privacy notice is public and says what Aegis keeps from Jira and GitHub", async ({ page }) => {
  await page.goto("/privacy");
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole("heading", { name: "Privacy", level: 1 })).toBeVisible();

  const jira = page.getByRole("region", { name: "Jira" });
  await expect(jira).toContainText("It stores no Atlassian account IDs, names or email addresses.");
  await expect(jira).toContainText("never edits, moves or deletes an issue");
  const revoke = jira.getByRole("link", { name: "id.atlassian.com/manage-profile/apps" });
  await expect(revoke).toHaveAttribute("href", "https://id.atlassian.com/manage-profile/apps");
  await expect(revoke).toHaveAttribute("rel", "noopener noreferrer");

  await expect(page.getByRole("region", { name: "GitHub" }))
    .toContainText("An access token pasted for an import is used for that import only and never stored.");
  await expect(page.getByRole("region", { name: "AI drafting" }))
    .toContainText("It never sends access tokens, repository files or anything read from Jira.");
  await expect(page.getByRole("region", { name: "Contact" })).toContainText("Contact whoever runs this Aegis deployment.");
});

test("the landing page's footer links to the privacy notice", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("contentinfo").getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
});
