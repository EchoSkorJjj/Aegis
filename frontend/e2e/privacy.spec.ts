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
  const drafting = page.getByRole("region", { name: "AI drafting" });
  await expect(drafting).toContainText("It never sends access tokens, the service's code, or anything read from Jira.");
  await expect(drafting).toContainText("how many owners each service has, never who they are");
  // E11-5: only a few lines where the code imports, uses or names the vulnerable part leave, when someone asks, and no
  // code is kept; the fix commit comes from its public host.
  const reachability = page.getByRole("region", { name: "AI reachability assessment" });
  await expect(reachability).toContainText("the lines where the code imports or uses the vulnerable package, and the"
    + " lines that name what the advisory or its fix names, up to six places, with three lines on either side");
  await expect(reachability).toContainText("Nothing else from the repository is sent");
  await expect(reachability).toContainText("the fix commit's changes from its public host");
  await expect(reachability).toContainText("never the code");
  await expect(reachability).toContainText("It never changes an impact score or decides anything.");
  // CS203-130: the dashboard's daily records keep owners' display names as they were, after they leave.
  const history = page.getByRole("region", { name: "Dashboard history" });
  await expect(history).toContainText("the owner's display name as they were that day");
  await expect(history).toContainText("It records no email addresses.");
  await expect(history).toContainText("keep an owner's name after they are unassigned or leave the organization");
  await expect(history).toContainText("does not delete them on its own");
  await expect(page.getByRole("region", { name: "Contact" })).toContainText("Contact whoever runs this Aegis deployment.");
});

test("the landing page's footer links to the privacy notice", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("contentinfo").getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
});
