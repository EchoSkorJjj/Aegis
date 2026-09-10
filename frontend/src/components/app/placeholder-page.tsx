/**
 * A simple placeholder for pages that are scaffolded in the navigation but not yet built.
 * Mirrors the dashboard layout (title + description + a dashed content area) so the shell
 * looks consistent while the real features are implemented.
 */
export function PlaceholderPage({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>

      <div className="flex min-h-64 items-center justify-center rounded-xl border border-dashed border-border p-10 text-center text-sm text-muted-foreground">
        This page is a placeholder — the {title.toLowerCase()} experience will live here.
      </div>
    </div>
  );
}
