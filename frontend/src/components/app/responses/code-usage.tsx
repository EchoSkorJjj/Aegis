import { formatDateTime } from "@/components/app/responses/response-status";
import type { CodeUsage, ImportScanSummary, UpgradeRecommendation } from "@/lib/types";

const LANGUAGE_LABELS: Record<string, string> = {
  npm: "JavaScript/TypeScript",
  pypi: "Python",
  maven: "Java/Kotlin",
};

/**
 * Where the service's code imports a vulnerable dependency (E11-3): the lines, linked to them in the repository at
 * the commit read; or that nothing imports it; or that it's unknown, and why — never shown as unused without the
 * evidence. For a package another one pulls in that the code doesn't import itself, also where it imports that one.
 */
export function CodeUsageLine({ item }: { item: Pick<UpgradeRecommendation, "usage" | "viaUsage" | "via"> }) {
  const { usage, viaUsage, via } = item;
  return (
    <span className="mt-1 block text-xs text-muted-foreground">
      <UsageText usage={usage} subject="Imported" />
      {via && viaUsage && usage.status !== "IMPORTED" && (
        <span className="mt-0.5 block">
          <span className="font-mono">{via.name}</span>, which pulls it in:{" "}
          <UsageText usage={viaUsage} subject="imported" inline />
        </span>
      )}
    </span>
  );
}

function UsageText({ usage, subject, inline = false }: { usage: CodeUsage; subject: string; inline?: boolean }) {
  if (usage.status === "IMPORTED") {
    const more = usage.siteCount - usage.sites.length;
    return (
      <details className={inline ? "inline" : undefined}>
        <summary className="cursor-pointer font-medium text-foreground">
          {subject} in {usage.siteCount} {usage.siteCount === 1 ? "place" : "places"}
        </summary>
        <ul className="mt-1 space-y-0.5 pl-3">
          {usage.sites.map((site) => (
            <li key={`${site.path}:${site.line}`} className="font-mono break-all">
              {site.url ? (
                <a
                  href={site.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-primary underline-offset-4 hover:underline"
                >
                  {site.path}:{site.line}
                </a>
              ) : (
                `${site.path}:${site.line}`
              )}
            </li>
          ))}
          {more > 0 && <li>and {more} more</li>}
        </ul>
      </details>
    );
  }
  if (usage.status === "NOT_IMPORTED") {
    return <span title={usage.reason ?? undefined}>Not imported by the service&apos;s code</span>;
  }
  return <span>Import unknown: {usage.reason}</span>;
}

/**
 * How the code was read, under the service's vulnerable dependencies: the commit and date, the languages and files, and
 * where the scan stopped early — or that it hasn't been read, so every dependency's import is unknown.
 */
export function ImportScanNote({ scan }: { scan: ImportScanSummary | null | undefined }) {
  if (!scan) {
    return (
      <p className="text-xs text-muted-foreground">
        Where the code imports each dependency shows once the SBOM is imported from the service&apos;s repository.
      </p>
    );
  }
  const languages = scan.languages.map((language) => LANGUAGE_LABELS[language] ?? language).join(", ");
  return (
    <p className="text-xs text-muted-foreground">
      Imports read from {scan.filesRead ?? 0} {scan.filesRead === 1 ? "file" : "files"}
      {languages && ` (${languages})`}
      {scan.revision && (
        <>
          {" "}
          at commit <span className="font-mono">{scan.revision.slice(0, 7)}</span>
        </>
      )}{" "}
      on {formatDateTime(scan.scannedAt)}.{scan.limit && ` ${scan.limit}.`}
    </p>
  );
}
