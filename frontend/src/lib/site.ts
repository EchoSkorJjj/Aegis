/**
 * Central, swappable site config. Rebrand the template by editing this one file
 * (e.g. for Aegis: change name/description). Self-registration is NOT here — it is
 * controlled by the backend (`REGISTRATION_ENABLED`) and read via GET /auth/config,
 * so one flag drives both the server enforcement and the UI (see lib/server/config.ts).
 */
export const siteConfig = {
  name: "Vertex",
  description:
    "The work tracker your team will actually enjoy using. Plan, track, and ship — without the busywork.",
  // Top-nav links. `#` entries are template placeholders to point at your own pages.
  nav: [
    { title: "Features", href: "#features" },
    { title: "Pricing", href: "#" },
    { title: "Docs", href: "#" },
  ],
} as const;

export type SiteConfig = typeof siteConfig;
