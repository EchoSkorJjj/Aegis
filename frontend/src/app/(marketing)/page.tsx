import type { Metadata } from "next";
import Link from "next/link";
import {
  ArrowRight,
  Boxes,
  Check,
  Gauge,
  GitPullRequest,
  Network,
  Rss,
  ShieldCheck,
  TrendingUp,
  UserCheck,
  Users,
} from "lucide-react";

import { buttonVariants } from "@/components/ui/button";
import { getAuthConfig } from "@/lib/server/config";
import { siteConfig } from "@/lib/site";
import { cn } from "@/lib/utils";

export const metadata: Metadata = {
  title: { absolute: `${siteConfig.name}: know what every advisory means for your services` },
};

// The same colours the app's queue uses for each impact tier.
const TIER_CLASS = {
  Critical: "bg-red-500/10 text-red-600 dark:text-red-400",
  High: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
  Medium: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
} as const;

// An illustration of the Recommended upgrades queue, scored on its 0–100 scale (Critical from 90, High from 70,
// Medium from 40). The advisories are real; the services are made up.
const previewRows: {
  name: string;
  from: string;
  to: string;
  cve: string;
  ecosystem: string;
  service: string;
  score: number;
  tier: keyof typeof TIER_CLASS;
  drafted: boolean;
}[] = [
  {
    name: "log4j-core",
    from: "2.14.1",
    to: "2.17.1",
    cve: "CVE-2021-44228",
    ecosystem: "Maven",
    service: "payments-api",
    score: 96.0,
    tier: "Critical",
    drafted: false,
  },
  {
    name: "jackson-databind",
    from: "2.13.2",
    to: "2.13.4.2",
    cve: "CVE-2022-42003",
    ecosystem: "Maven",
    service: "ledger-service",
    score: 75.0,
    tier: "High",
    drafted: true,
  },
  {
    name: "lodash",
    from: "4.17.20",
    to: "4.17.21",
    cve: "CVE-2021-23337",
    ecosystem: "npm",
    service: "customer-portal",
    score: 72.0,
    tier: "High",
    drafted: false,
  },
  {
    name: "requests",
    from: "2.25.1",
    to: "2.31.0",
    cve: "CVE-2023-32681",
    ecosystem: "PyPI",
    service: "risk-engine",
    score: 48.8,
    tier: "Medium",
    drafted: false,
  },
];

const steps = [
  {
    icon: Boxes,
    title: "Map your estate",
    text: "Link each service's repository and Aegis builds its SBOM. Record who owns each service, how critical it is and whether it faces the internet.",
  },
  {
    icon: Rss,
    title: "Watch for change",
    text: "Advisories from NVD and OSV, vendor status pages and security news, pulled on a schedule and kept to one record per CVE.",
  },
  {
    icon: Network,
    title: "Match every service",
    text: "Ecosystem-aware version matching through each service's own dependency graph, direct and transitive, with the evidence for every match.",
  },
  {
    icon: Gauge,
    title: "Rank the impact",
    text: "Severity × exposure × business criticality, from critical to low, with every factor of every score shown.",
  },
  {
    icon: UserCheck,
    title: "Draft, then decide",
    text: "Aegis drafts the response. A person approves it or dismisses it with a reason, and only an approval reaches GitHub or Jira.",
  },
];

const capabilities = [
  {
    icon: Boxes,
    title: "Your estate, mapped",
    text: "Services, components, vendors and owners in one inventory, each service with its own dependency graph. Used by shows every service on a package, in any version.",
  },
  {
    icon: Rss,
    title: "Every source of change",
    text: "Vulnerabilities from NVD and OSV across npm, PyPI, Maven, Go, crates.io and RubyGems, alongside vendor outages and security news.",
  },
  {
    icon: Gauge,
    title: "Impact you can explain",
    text: "A score per service and per owner, with the factor holding each one down, so any ranking can be checked by hand.",
  },
  {
    icon: TrendingUp,
    title: "Upgrades found for you",
    text: "Every vulnerable dependency across your services, highest impact first, with the lowest version that clears every advisory. No CVE hunting.",
  },
  {
    icon: GitPullRequest,
    title: "Responses, drafted",
    text: "An upgrade pull request on GitHub for Maven, npm and PyPI, a risk ticket for Jira, and a DORA notification memo when a service is materially affected.",
  },
  {
    icon: Users,
    title: "Four eyes, built in",
    text: "Analysts, service owners and compliance each see and do their own part. Whoever drafts a pull request never approves it, and each organization's data is kept apart.",
  },
];

const safeguards = [
  "Matching and scoring are deterministic code, and every decision is a person's.",
  "The AI writes only a draft's narrative, its summary, risk and suggested actions, and cites the advisories it drew on.",
  "Pull requests open as drafts and are never merged. Tickets are filed only on approval. Regulator memos are never sent.",
  "Every response records who drafted it and who decided, with the reason for any dismissal.",
  "Sign-in asks for a second factor: an email code, an authenticator app or a passkey.",
];

const integrations = ["GitHub", "Jira", "NVD", "OSV", "CycloneDX"];

export default async function Home() {
  // Sign-up can be switched off (invite-only); the calls to action follow the backend's setting.
  const { registrationEnabled } = await getAuthConfig();
  const primaryCta = registrationEnabled
    ? { label: "Get started", href: "/signup" }
    : { label: "Log in", href: "/login" };

  return (
    <>
      {/* Hero */}
      <section className="relative isolate overflow-hidden">
        <div
          aria-hidden
          className="absolute inset-0 -z-10 bg-[linear-gradient(to_right,var(--border)_1px,transparent_1px),linear-gradient(to_bottom,var(--border)_1px,transparent_1px)] bg-[size:48px_48px] opacity-60 [mask-image:radial-gradient(ellipse_at_top,black_20%,transparent_70%)]"
        />
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center px-4 pt-20 pb-16 text-center sm:px-6 md:pt-28">
          <span className="inline-flex items-center gap-2 rounded-full border border-border bg-background/80 px-3 py-1 text-xs font-medium text-muted-foreground">
            <ShieldCheck className="size-3.5" aria-hidden />
            Supply-chain and operational resilience
          </span>

          <h1 className="mt-6 max-w-3xl text-4xl font-semibold tracking-tight text-balance sm:text-5xl md:text-6xl">
            Know what every advisory means for your services
          </h1>
          <p className="mt-5 max-w-2xl text-lg text-muted-foreground text-pretty">
            {siteConfig.name} maps your services and their dependencies, matches each new vulnerability and vendor
            outage against them, ranks the impact, and drafts the response, whether an upgrade pull request, a risk
            ticket or a regulator notice, for a person to approve.
          </p>

          <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row">
            <Link href={primaryCta.href} className={cn(buttonVariants({ size: "lg" }), "gap-2")}>
              {primaryCta.label}
              <ArrowRight className="size-4" aria-hidden />
            </Link>
            <Link href="#how-it-works" className={cn(buttonVariants({ variant: "outline", size: "lg" }))}>
              See how it works
            </Link>
          </div>

          <UpgradesPreview />

          <div className="mt-10 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-sm text-muted-foreground">
            <span>Works with</span>
            {integrations.map((name) => (
              <span key={name} className="font-medium text-foreground/80">
                {name}
              </span>
            ))}
          </div>
        </div>
      </section>

      {/* How it works */}
      <section id="how-it-works" className="scroll-mt-16 border-t border-border/60 bg-muted/20">
        <div className="mx-auto w-full max-w-6xl px-4 py-20 sm:px-6">
          <SectionHeading
            title="From advisory to approved fix"
            description="Aegis does the searching, matching and scoring. People make the calls."
          />
          <ol className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            {steps.map((step, index) => (
              <li key={step.title} className="rounded-xl border border-border bg-card p-5">
                <div className="flex items-center justify-between">
                  <div className="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <step.icon className="size-4.5" aria-hidden />
                  </div>
                  <span className="font-mono text-xs text-muted-foreground">0{index + 1}</span>
                </div>
                <h3 className="mt-4 font-medium">{step.title}</h3>
                <p className="mt-2 text-sm text-muted-foreground">{step.text}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* Capabilities */}
      <section id="capabilities" className="scroll-mt-16 border-t border-border/60">
        <div className="mx-auto w-full max-w-6xl px-4 py-20 sm:px-6">
          <SectionHeading
            title="Built for teams that run many services"
            description="One place to see what changed, what it touches, and what to do about it."
          />
          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {capabilities.map((capability) => (
              <div key={capability.title} className="rounded-xl border border-border bg-card p-6">
                <div className="flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <capability.icon className="size-5" aria-hidden />
                </div>
                <h3 className="mt-4 font-medium">{capability.title}</h3>
                <p className="mt-2 text-sm text-muted-foreground">{capability.text}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Safeguards */}
      <section id="safeguards" className="scroll-mt-16 border-t border-border/60 bg-muted/20">
        <div className="mx-auto grid w-full max-w-6xl gap-10 px-4 py-20 sm:px-6 lg:grid-cols-[1fr_1.2fr] lg:items-center">
          <div>
            <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">
              AI where it helps, never in charge
            </h2>
            <p className="mt-4 text-muted-foreground text-pretty">
              {siteConfig.name} uses AI to write drafts, never to decide. Matching and scoring are plain code, and
              everything that changes your code or your tracker waits for a person.
            </p>
            <Link
              href="/privacy"
              className="mt-6 inline-flex items-center gap-1.5 text-sm font-medium underline-offset-4 hover:underline"
            >
              What {siteConfig.name} does with your data
              <ArrowRight className="size-3.5" aria-hidden />
            </Link>
          </div>
          <ul className="space-y-3">
            {safeguards.map((item) => (
              <li key={item} className="flex gap-3 rounded-xl border border-border bg-card p-4">
                <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
                  <Check className="size-3" aria-hidden />
                </span>
                <span className="text-sm">{item}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      {/* Call to action */}
      <section className="border-t border-border/60">
        <div className="mx-auto w-full max-w-6xl px-4 py-20 sm:px-6">
          <div className="rounded-2xl border border-border bg-primary px-6 py-14 text-center text-primary-foreground sm:px-8">
            <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">
              Put your next advisory in context
            </h2>
            <p className="mx-auto mt-3 max-w-xl text-primary-foreground/80 text-pretty">
              Create an organization, link a repository, and see which of your services the next CVE reaches.
            </p>
            <div className="mt-8 flex justify-center">
              <Link
                href={primaryCta.href}
                className={cn(buttonVariants({ variant: "secondary", size: "lg" }), "gap-2")}
              >
                {primaryCta.label}
                <ArrowRight className="size-4" aria-hidden />
              </Link>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}

function SectionHeading({ title, description }: { title: string; description: string }) {
  return (
    <div className="mx-auto max-w-2xl text-center">
      <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">{title}</h2>
      <p className="mt-3 text-muted-foreground text-pretty">{description}</p>
    </div>
  );
}

/** A still of the Recommended upgrades queue: read as one image, since nothing in it is interactive. */
function UpgradesPreview() {
  return (
    <div
      role="img"
      aria-label="Example: Aegis's recommended upgrades, highest impact first, each with its fix and a draft response"
      className="mt-16 w-full max-w-5xl text-left"
    >
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="flex items-center gap-1.5 border-b border-border bg-muted/40 px-4 py-3">
          <span className="size-3 rounded-full bg-muted-foreground/25" />
          <span className="size-3 rounded-full bg-muted-foreground/25" />
          <span className="size-3 rounded-full bg-muted-foreground/25" />
          <span className="ml-3 truncate text-xs text-muted-foreground">Responses / Review &amp; approve</span>
        </div>
        <div className="px-4 pt-4 pb-2 sm:px-6">
          <div className="text-sm font-medium">
            Recommended upgrades <span className="text-muted-foreground">({previewRows.length})</span>
          </div>
          <div className="text-xs text-muted-foreground">
            Every vulnerable dependency across your services, highest impact first.
          </div>
        </div>
        <div className="hidden grid-cols-[minmax(0,2.2fr)_minmax(0,1fr)_8.5rem_7.5rem] gap-4 border-b border-border px-6 py-2 text-xs font-medium text-muted-foreground sm:grid">
          <span>Dependency</span>
          <span>Service</span>
          <span>Impact</span>
          <span />
        </div>
        <div className="divide-y divide-border">
          {previewRows.map((row) => (
            <div
              key={row.name}
              className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4 gap-y-1 px-4 py-3 sm:grid-cols-[minmax(0,2.2fr)_minmax(0,1fr)_8.5rem_7.5rem] sm:px-6"
            >
              <div className="min-w-0">
                <span className="font-medium">{row.name}</span>{" "}
                <span className="font-mono text-xs text-muted-foreground">
                  {row.from} → <span className="text-foreground">{row.to}</span>
                </span>
                <span className="mt-0.5 block text-xs text-muted-foreground">
                  Fixes <span className="font-mono text-foreground">{row.cve}</span> · {row.ecosystem}
                  <span className="sm:hidden"> · {row.service}</span>
                </span>
              </div>
              <span className="hidden truncate pt-0.5 text-sm sm:block">{row.service}</span>
              <span className="inline-flex items-center gap-1.5 pt-0.5">
                <span className="hidden rounded-md border px-1.5 py-0.5 font-mono text-xs font-semibold tabular-nums sm:inline">
                  {row.score.toFixed(1)}
                </span>
                <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium", TIER_CLASS[row.tier])}>
                  {row.tier}
                </span>
              </span>
              <span className="hidden justify-end sm:flex">
                {row.drafted ? (
                  <span className="text-xs font-medium text-muted-foreground">Response drafted</span>
                ) : (
                  <span className={cn(buttonVariants({ variant: "outline", size: "sm" }), "pointer-events-none")}>
                    Draft response
                  </span>
                )}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
