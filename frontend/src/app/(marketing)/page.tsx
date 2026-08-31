import Link from "next/link";
import {
  ArrowRight,
  GitPullRequest,
  LineChart,
  ShieldCheck,
  Sparkles,
  SquareKanban,
  Users,
  Zap,
} from "lucide-react";

import { buttonVariants } from "@/components/ui/button";
import { getAuthConfig } from "@/lib/server/config";
import { siteConfig } from "@/lib/site";
import { cn } from "@/lib/utils";

const features = [
  {
    icon: SquareKanban,
    title: "Boards that keep up",
    description:
      "Drag work across flexible boards and backlogs. Your process, not ours.",
  },
  {
    icon: GitPullRequest,
    title: "Connected to your code",
    description:
      "Link issues to branches and pull requests so status updates itself.",
  },
  {
    icon: LineChart,
    title: "Insight, not overhead",
    description:
      "Burndowns, velocity, and dashboards that answer “are we on track?” at a glance.",
  },
  {
    icon: Zap,
    title: "Automate the busywork",
    description:
      "Rules assign, move, and notify — so nobody has to chase updates.",
  },
  {
    icon: Users,
    title: "Built for teams",
    description:
      "Roles, mentions, and shared views keep everyone pointed the same way.",
  },
  {
    icon: ShieldCheck,
    title: "Enterprise-ready",
    description:
      "SSO, granular roles, and audit trails are here from day one.",
  },
];

export default async function Home() {
  // The primary call-to-action changes when self-registration is disabled (invite-only).
  const { registrationEnabled } = await getAuthConfig();
  const primaryCta = registrationEnabled
    ? { label: "Get started free", href: "/signup" }
    : { label: "Log in", href: "/login" };

  return (
    <>
      {/* Hero */}
      <section className="mx-auto flex w-full max-w-6xl flex-col items-center px-4 py-20 text-center sm:px-6 md:py-28">
        <span className="inline-flex items-center gap-2 rounded-full border border-border bg-muted/40 px-3 py-1 text-xs font-medium text-muted-foreground">
          <Sparkles className="size-3.5" />
          New — timeline view is here
        </span>

        <h1 className="mt-6 max-w-3xl text-4xl font-semibold tracking-tight text-balance sm:text-5xl md:text-6xl">
          The work tracker your team will actually enjoy using
        </h1>
        <p className="mt-5 max-w-xl text-lg text-muted-foreground text-pretty">
          Plan, track, and ship in one place. {siteConfig.name} gives your team
          the structure of Jira without the busywork.
        </p>

        <div className="mt-8 flex flex-col items-center gap-3 sm:flex-row">
          <Link
            href={primaryCta.href}
            className={cn(buttonVariants({ size: "lg" }), "gap-2")}
          >
            {primaryCta.label}
            <ArrowRight className="size-4" />
          </Link>
          <Link
            href="#features"
            className={cn(buttonVariants({ variant: "outline", size: "lg" }))}
          >
            See how it works
          </Link>
        </div>

        {/* Product preview (static mock) */}
        <div className="mt-16 w-full max-w-5xl">
          <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
            <div className="flex items-center gap-1.5 border-b border-border bg-muted/40 px-4 py-3">
              <span className="size-3 rounded-full bg-muted-foreground/25" />
              <span className="size-3 rounded-full bg-muted-foreground/25" />
              <span className="size-3 rounded-full bg-muted-foreground/25" />
              <div className="ml-3 h-5 w-56 rounded bg-muted" />
            </div>
            <div className="grid grid-cols-3 gap-3 p-3 sm:gap-4 sm:p-6">
              {[
                { name: "To do", count: 4, cards: 3 },
                { name: "In progress", count: 3, cards: 2 },
                { name: "Done", count: 6, cards: 2 },
              ].map((column) => (
                <div key={column.name} className="rounded-lg bg-muted/40 p-3">
                  <div className="mb-3 flex items-center justify-between">
                    <span className="text-xs font-medium text-muted-foreground">
                      {column.name}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {column.count}
                    </span>
                  </div>
                  <div className="space-y-2">
                    {Array.from({ length: column.cards }).map((_, index) => (
                      <div
                        key={index}
                        className="rounded-md border border-border bg-card p-3 text-left shadow-xs"
                      >
                        <div className="h-2 w-3/4 rounded bg-muted" />
                        <div className="mt-3 flex items-center gap-2">
                          <div className="size-4 rounded-full bg-primary/20" />
                          <div className="h-2 w-10 rounded bg-muted" />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      {/* Features */}
      <section id="features" className="border-t border-border/60 bg-muted/20">
        <div className="mx-auto w-full max-w-6xl px-4 py-20 sm:px-6">
          <div className="mx-auto max-w-2xl text-center">
            <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">
              Everything your team needs to ship
            </h2>
            <p className="mt-3 text-muted-foreground">
              A focused set of tools that stays out of the way.
            </p>
          </div>

          <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {features.map((feature) => (
              <div
                key={feature.title}
                className="rounded-xl border border-border bg-card p-6"
              >
                <div className="flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <feature.icon className="size-5" />
                </div>
                <h3 className="mt-4 font-medium">{feature.title}</h3>
                <p className="mt-2 text-sm text-muted-foreground">
                  {feature.description}
                </p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Call to action */}
      <section className="border-t border-border/60">
        <div className="mx-auto w-full max-w-6xl px-4 py-20 sm:px-6">
          <div className="rounded-2xl border border-border bg-primary px-6 py-14 text-center text-primary-foreground sm:px-8">
            <h2 className="text-3xl font-semibold tracking-tight sm:text-4xl">
              Ready to get started?
            </h2>
            <p className="mx-auto mt-3 max-w-md text-primary-foreground/80">
              Set up your workspace in minutes — no credit card required.
            </p>
            <div className="mt-8 flex justify-center">
              <Link
                href={primaryCta.href}
                className={cn(
                  buttonVariants({ variant: "secondary", size: "lg" }),
                  "gap-2",
                )}
              >
                {primaryCta.label}
                <ArrowRight className="size-4" />
              </Link>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}
