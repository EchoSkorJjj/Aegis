import type { UpgradeEdit } from "@/lib/types";

/**
 * What an upgrade pull request changes, one line per manifest it edits: the package, the file, and the version
 * it moves from and to — marked as an override, with what pulls it in, when no manifest declares the package. The
 * same table on the response page and the pull request's own page.
 */
export function UpgradeEditsTable({ edits }: { edits: UpgradeEdit[] }) {
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full text-sm">
        <caption className="sr-only">What the pull request changes</caption>
        <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
          <tr>
            <th scope="col" className="px-3 py-2 font-medium">
              Package
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Manifest
            </th>
            <th scope="col" className="px-3 py-2 font-medium">
              Version
            </th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {edits.map((edit) => (
            <tr key={`${edit.componentId}:${edit.manifestPath}`}>
              <td className="px-3 py-2 text-xs break-all">
                <span className="font-mono">{edit.packageName}</span>
                {edit.override && (
                  <span className="mt-0.5 block text-muted-foreground">
                    Override{edit.override.via ? <> · through <span className="font-mono">{edit.override.via}</span></> : null}
                  </span>
                )}
              </td>
              <td className="px-3 py-2 font-mono text-xs break-all">{edit.manifestPath}</td>
              <td className="px-3 py-2 font-mono text-xs whitespace-nowrap">
                {edit.fromVersion} → <span className="font-medium text-foreground">{edit.toVersion}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * One line for a list: "axios 1.5.1 → 1.8.2", "hono 4.12.7 → 4.13.7 (override)" when no manifest declares it, or
 * "axios → 1.8.2 in 2 manifests" for several.
 */
export function describeEdits(edits: UpgradeEdit[]): string {
  if (edits.length === 0) return "";
  if (edits.length === 1) {
    const [edit] = edits;
    return `${edit.packageName} ${edit.fromVersion} → ${edit.toVersion}${edit.override ? " (override)" : ""}`;
  }
  const packages = [...new Set(edits.map((edit) => edit.packageName))];
  const targets = [...new Set(edits.map((edit) => edit.toVersion))];
  const manifests = new Set(edits.map((edit) => edit.manifestPath)).size;
  const what =
    packages.length === 1
      ? `${packages[0]}${targets.length === 1 ? ` → ${targets[0]}` : ""}`
      : `${packages[0]} and ${packages.length - 1} more`;
  return manifests > 1 ? `${what} in ${manifests} manifests` : what;
}
