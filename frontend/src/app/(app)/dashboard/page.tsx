import type { Metadata } from "next";

export const metadata: Metadata = { title: "Dashboard" };

export default function DashboardPage() {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
        <p className="text-sm text-muted-foreground">
          Welcome back. Your workspace content goes here.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {["Open issues", "In progress", "Completed"].map((label) => (
          <div key={label} className="rounded-xl border border-border bg-card p-6">
            <div className="text-sm text-muted-foreground">{label}</div>
            <div className="mt-2 text-3xl font-semibold tabular-nums">—</div>
          </div>
        ))}
      </div>

      <div className="flex min-h-64 items-center justify-center rounded-xl border border-dashed border-border p-10 text-center text-sm text-muted-foreground">
        This is where the dashboard content will live.
      </div>
    </div>
  );
}
