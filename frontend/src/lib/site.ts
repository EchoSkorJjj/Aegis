/**
 * Central site config: the name, the description every page's metadata and the footer use, and the marketing
 * header's links. Self-registration is NOT here — it is controlled by the backend (`REGISTRATION_ENABLED`) and read
 * via GET /auth/config, so one flag drives both the server enforcement and the UI (see lib/server/config.ts).
 */
export const siteConfig = {
  name: "Aegis",
  description:
    "Aegis matches every new vulnerability and vendor outage against your services, ranks the impact, and drafts " +
    "the response for a person to approve.",
  // Top-nav links: sections of the landing page, written from the root so they work from /privacy too.
  nav: [
    { title: "How it works", href: "/#how-it-works" },
    { title: "Capabilities", href: "/#capabilities" },
    { title: "Safeguards", href: "/#safeguards" },
  ],
} as const;

export type SiteConfig = typeof siteConfig;
