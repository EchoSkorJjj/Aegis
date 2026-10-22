import type { Metadata } from "next";
import { connection } from "next/server";

import { siteConfig } from "@/lib/site";

export const metadata: Metadata = { title: "Privacy" };

/** When this notice last changed in substance. */
const UPDATED = "23 October 2026";

/**
 * What Aegis does with data from the Jira and GitHub accounts an organization connects — the page
 * Atlassian shows people when they grant Aegis access to Jira, so it is public. Who runs this deployment
 * and how to reach them are runtime settings (`PRIVACY_OPERATOR`, `PRIVACY_CONTACT`), so one image serves
 * every deployment.
 */
export default async function PrivacyPage() {
  // Read the settings when the page is requested, not when it was built.
  await connection();
  const operator = process.env.PRIVACY_OPERATOR?.trim() || null;
  const contact = contactLink(process.env.PRIVACY_CONTACT?.trim() || null);
  const name = siteConfig.name;

  return (
    <article className="mx-auto w-full max-w-3xl px-4 py-16 sm:px-6">
      <p className="text-sm text-muted-foreground">Last updated {UPDATED}</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Privacy</h1>
      <p className="mt-4 text-muted-foreground">
        What {name} does with data from the Jira and GitHub accounts an organization connects to it.
        {operator && ` This ${name} deployment is run by ${operator}.`}
      </p>

      <Section id="jira" title="Jira">
        <Part title={`What ${name} accesses`}>
          <p>
            With the permissions you grant (View Jira issue data, and Create and manage issues, kept until you
            revoke them), {name}:
          </p>
          <ul>
            <li>
              lists the Jira projects you can create issues in, and a chosen project&apos;s issue types, so your
              organization&apos;s admin can choose where risk tickets go;
            </li>
            <li>
              creates an issue in that project each time a person in your {name} organization approves a risk
              ticket. The issue holds the ticket&apos;s title, description, labels, priority and due date.
            </li>
          </ul>
          <p>
            It never reads existing issues, comments, attachments or user profiles, and never edits, moves or
            deletes an issue.
          </p>
        </Part>
        <Part title={`What ${name} stores`}>
          <ul>
            <li>
              The access and refresh tokens, encrypted (AES-256-GCM) so that only your {name} organization&apos;s
              connection can use them.
            </li>
            <li>The Jira site&apos;s ID, name and address, and the project and issue type chosen for tickets.</li>
            <li>Which member of your {name} organization connected Jira, and when.</li>
            <li>The key and link of each issue {name} creates.</li>
          </ul>
          <p>It stores no Atlassian account IDs, names or email addresses.</p>
        </Part>
        <Part title="Removing access">
          <ul>
            <li>
              An organization admin can disconnect Jira in {name} at any time, under Organization → Integrations.{" "}
              {name} deletes the tokens and settings at once.
            </li>
            <li>
              You can also revoke {name} from your Atlassian account at{" "}
              <ExternalLink href="https://id.atlassian.com/manage-profile/apps">
                id.atlassian.com/manage-profile/apps
              </ExternalLink>
              .
            </li>
            <li>Issues {name} already created stay in your Jira, like any other issue.</li>
          </ul>
        </Part>
      </Section>

      <Section id="github" title="GitHub">
        <Part title={`What ${name} accesses`}>
          <ul>
            <li>
              To connect, an organization admin signs in to GitHub once. {name} uses that sign-in only to check
              which installations of the {name} GitHub App they control, then revokes it. It is never stored.
            </li>
            <li>
              Through a connected installation, {name} reads a service&apos;s repository to import its software
              bill of materials (SBOM) and to find where its code imports each package; it reads the code as text
              and never runs it. Only when a person approves an upgrade does it re-read the manifests it
              edits, commit the version changes to an <code className="font-mono text-sm">aegis/</code> branch and
              open a draft pull request. It never merges.
            </li>
            <li>
              Its GitHub tokens last an hour, reach one repository at a time with the least access the job needs,
              and are kept in memory only.
            </li>
          </ul>
        </Part>
        <Part title={`What ${name} stores`}>
          <ul>
            <li>
              For each connected installation: its ID, the GitHub account&apos;s name, ID and type, and who
              connected it and when.
            </li>
            <li>The repository address and branch linked to each service.</li>
            <li>
              From each SBOM import, the packages and versions found and which manifest declares each, and where the
              code imports each package — the module names, file paths and line numbers, never the code itself. The
              repository is cloned into a temporary folder that is deleted when the import finishes.
            </li>
            <li>An access token pasted for an import is used for that import only and never stored.</li>
          </ul>
        </Part>
        <Part title="Removing access">
          <ul>
            <li>An organization admin can disconnect an installation in {name} at any time.</li>
            <li>Uninstalling the {name} GitHub App on GitHub withdraws its access entirely.</li>
          </ul>
        </Part>
      </Section>

      <Section id="ai" title="AI drafting">
        <p>
          When this deployment has an AI provider configured, {name} uses it to draft the text of pull requests,
          risk tickets and regulator notifications. It sends the provider the change&apos;s published description
          and {name}&apos;s own facts about it: the affected services, packages, versions and vendors, their impact
          scores, and how many owners each service has, never who they are. It never sends access tokens, the
          service&apos;s code, or anything read from Jira. A person reviews every draft before anything is filed,
          opened or finalised.
        </p>
      </Section>

      <Section id="ai-reachability" title="AI reachability assessment">
        <p>
          When someone asks the AI whether a service&apos;s code uses the vulnerable part of a package it imports, or
          drafts a risk ticket for such a vulnerability, {name} fetches the fix commit&apos;s changes from its public
          host (GitHub or GitLab), when the advisory names one, and reads the repository again at the commit its last
          import read — only the source files in the package&apos;s language, deleted when done. It sends the AI
          provider the vulnerability&apos;s published description, what the fix changed, the package&apos;s name, how
          often the code names what the advisory names, and the lines where the code imports or uses the vulnerable
          package, and the lines that name what the advisory or its fix names, up to six places, with three lines on
          either side and each file&apos;s path — after removing anything that looks like a secret or personal data.
          Nothing else from the repository is sent, and the AI can&apos;t search the code for credentials.
        </p>
        <p>
          {name} keeps the answer — called, not called or can&apos;t tell — the AI&apos;s reason, what it suggests a
          reviewer check, the names the code was searched for, and the file paths and line numbers it cites, never
          the code. The answer is a suggestion for a person. It never changes an impact score or decides anything.
        </p>
      </Section>

      <Section id="dashboard-history" title="Dashboard history">
        <p>
          Once a day, and whenever someone opens the dashboard, {name} records how many open material items the
          organization has and, for each service and each assigned owner, their open, critical and high counts and
          highest impact score, under the service&apos;s name and the owner&apos;s display name as they were that day.
          It records no email addresses.
        </p>
        <p>
          These daily records keep an owner&apos;s name after they are unassigned or leave the organization, and a
          service&apos;s name after it is renamed or deleted, so the trend shows who was responsible at the time.{" "}
          {name} does not delete them on its own; whoever runs this deployment can.
        </p>
      </Section>

      <Section id="storage" title="Where it is kept">
        <p>
          This data is stored in this {name} deployment&apos;s database, and each organization&apos;s data is kept
          apart from every other organization&apos;s. {name} does not sell it, or share it beyond what this
          notice describes.
        </p>
      </Section>

      <Section id="contact" title="Contact">
        {contact ? (
          <p>
            Questions or requests about this data:{" "}
            {contact.href ? <ExternalLink href={contact.href}>{contact.label}</ExternalLink> : contact.label}
          </p>
        ) : (
          <p>
            Contact whoever runs this {name} deployment. An organization admin can disconnect Jira or GitHub at
            any time.
          </p>
        )}
      </Section>
    </article>
  );
}

/** The contact as a link: an email address becomes a mailto link, an https address a link; anything else text. */
function contactLink(value: string | null): { href: string | null; label: string } | null {
  if (!value) return null;
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return { href: `mailto:${value}`, label: value };
  if (/^https:\/\/\S+$/.test(value)) return { href: value, label: value };
  return { href: null, label: value };
}

function Section({ id, title, children }: { id: string; title: string; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-heading`} className="mt-12 scroll-mt-24 space-y-6">
      <h2 id={`${id}-heading`} className="border-b pb-2 text-xl font-semibold tracking-tight">
        {title}
      </h2>
      <div className="space-y-6 leading-relaxed [&_p]:mt-2 [&_ul]:mt-2 [&_ul]:list-disc [&_ul]:space-y-1.5 [&_ul]:pl-5">
        {children}
      </div>
    </section>
  );
}

function Part({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="font-medium">{title}</h3>
      {children}
    </div>
  );
}

function ExternalLink({ href, children }: { href: string; children: React.ReactNode }) {
  // A web address opens in a new tab; a mailto link hands over to the mail app.
  const web = href.startsWith("https://");
  return (
    <a
      href={href}
      {...(web ? { target: "_blank", rel: "noopener noreferrer" } : {})}
      className="font-medium text-primary underline underline-offset-4 hover:decoration-2"
    >
      {children}
    </a>
  );
}
