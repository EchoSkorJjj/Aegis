"use client";

import { useEffect, useId, useState } from "react";
import { format, subDays } from "date-fns";
import { Download } from "lucide-react";

import { DateRangePicker } from "@/components/app/date-range-picker";
import { DECISION_ACTION, DECISION_SUBJECT, startOfDay } from "@/components/app/reports/decision-trail";
import { FilterSelect } from "@/components/app/responses/upgrade-recommendations";
import { StatusBadge, formatDateTime } from "@/components/app/responses/response-status";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, auditApi, inventoryApi, type EvidenceFile } from "@/lib/api";
import { canReadDecisionTrail } from "@/lib/roles";
import type { BusinessService, DecisionRecord, Page } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const DAY = "yyyy-MM-dd";
const PREVIEW = 5;

/**
 * Evidence export (E10 story 3): a service's decision trail for a period, downloaded as a PDF to hand an auditor or
 * regulator, or as CSV to work with. Both hold the same records; the PDF prints the CSV's SHA-256 so whoever holds
 * both can check they agree. The page previews what the period holds before anything is downloaded.
 */
export function EvidenceExport() {
  const { user, isLoading } = useAuth();
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!canReadDecisionTrail(user?.roles ?? [])) {
    return (
      <div className="mx-auto w-full max-w-4xl rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
        You don&apos;t have access to evidence exports — they&apos;re for analysts and compliance admins.
      </div>
    );
  }
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Evidence</h1>
        <p className="text-sm text-muted-foreground">
          Export how one service&apos;s risks were handled over a period: every decision on its risk tickets and
          upgrade pull requests, and on the regulator memos that cover it, with who took it, when, why and the
          response as it stood then. Download a PDF to hand an auditor or regulator, or CSV to work with.
        </p>
      </div>
      <ExportForm key={`${user?.orgId}:${user?.id}`} />
    </div>
  );
}

function ExportForm() {
  const id = useId();
  const [services, setServices] = useState<BusinessService[] | null>(null);
  const [serviceId, setServiceId] = useState("");
  // The last 90 days, today included, by default.
  const [period, setPeriod] = useState<{ from?: string; to?: string }>(() => ({
    from: format(subDays(new Date(), 89), DAY),
    to: format(new Date(), DAY),
  }));
  const [preview, setPreview] = useState<Page<DecisionRecord> | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"pdf" | "csv" | null>(null);
  const [saved, setSaved] = useState<EvidenceFile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    inventoryApi
      .listServices({ size: 100, sort: "name,asc" })
      .then((page) => {
        if (!cancelled) setServices(page.content);
      })
      .catch(() => {
        if (!cancelled) setServices([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Whole days in the reader's time zone: from the first day's start to the start of the day after the last.
  const from = period.from ? startOfDay(period.from) : undefined;
  const to = period.to ? startOfDay(period.to, 1) : period.from ? startOfDay(period.from, 1) : undefined;
  const ready = serviceId !== "" && from !== undefined && to !== undefined;

  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new preview for the new service or period
    setPreview(null);
    setPreviewError(null);
    auditApi
      .search({ serviceId, from, to, newestFirst: true, size: PREVIEW })
      .then((page) => {
        if (!cancelled) setPreview(page);
      })
      .catch((err) => {
        if (!cancelled) setPreviewError(err instanceof ApiError ? err.message : "Could not load the preview.");
      });
    return () => {
      cancelled = true;
    };
  }, [ready, serviceId, from, to]);

  const download = async (kind: "pdf" | "csv") => {
    if (!ready) return;
    setBusy(kind);
    setError(null);
    setSaved(null);
    try {
      const file = await auditApi.exportEvidence({
        serviceId,
        from,
        to,
        format: kind,
        zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      });
      save(file);
      setSaved(file);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not export the evidence. Please try again.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <FilterSelect
          id={`${id}-service`}
          label="Service"
          value={serviceId}
          allLabel={services === null ? "Loading services…" : "Choose a service"}
          onChange={(value) => {
            setServiceId(value);
            setSaved(null);
          }}
        >
          {services?.map((service) => (
            <option key={service.id} value={service.id}>
              {service.name}
            </option>
          ))}
        </FilterSelect>
        <DateRangePicker
          label="Period"
          from={period.from}
          to={period.to}
          onChange={(range) => {
            setPeriod(range);
            setSaved(null);
          }}
        />
      </div>

      <section aria-label="What the export holds" className="space-y-3 rounded-lg border p-4">
        {!ready ? (
          <p className="text-sm text-muted-foreground">
            {serviceId === "" ? "Choose a service" : "Choose a period"} to see what the export holds.
          </p>
        ) : previewError ? (
          <p className="text-sm text-red-600 dark:text-red-400">{previewError}</p>
        ) : preview === null ? (
          <Skeleton className="h-16 w-full" />
        ) : (
          <>
            <p className="text-sm font-medium" aria-live="polite">
              {preview.totalElements === 0
                ? "No decision was recorded for this service in this period."
                : preview.totalElements === 1
                  ? "1 decision record in this period"
                  : `${preview.totalElements} decision records in this period`}
            </p>
            {preview.content.length > 0 && (
              <ul className="space-y-1.5 text-sm">
                {preview.content.map((record) => (
                  <li key={record.id} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                    <span className="text-muted-foreground tabular-nums">{formatDateTime(record.occurredAt)}</span>
                    <StatusBadge tone={DECISION_ACTION[record.action].tone}>
                      {DECISION_ACTION[record.action].label}
                    </StatusBadge>
                    <span className="text-muted-foreground">{DECISION_SUBJECT[record.subject]}</span>
                    <span className="min-w-0 truncate">{record.changeTitle}</span>
                  </li>
                ))}
                {preview.totalElements > preview.content.length && (
                  <li className="text-xs text-muted-foreground">
                    and {preview.totalElements - preview.content.length} earlier, all in the export
                  </li>
                )}
              </ul>
            )}
          </>
        )}
      </section>

      <div className="flex flex-col gap-3 sm:flex-row">
        <Button disabled={!ready || busy !== null} onClick={() => download("pdf")}>
          <Download data-icon="inline-start" />
          {busy === "pdf" ? "Preparing PDF…" : "Download PDF"}
        </Button>
        <Button variant="outline" disabled={!ready || busy !== null} onClick={() => download("csv")}>
          <Download data-icon="inline-start" />
          {busy === "csv" ? "Preparing CSV…" : "Download CSV"}
        </Button>
      </div>

      {error && (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
      {saved && (
        <div role="status" className="space-y-1 rounded-lg border bg-muted/40 p-4 text-sm">
          <p>
            Saved <span className="font-medium">{saved.fileName}</span>:{" "}
            {saved.records === 1 ? "1 decision record" : `${saved.records} decision records`}.
          </p>
          <p className="text-muted-foreground">
            CSV SHA-256 <code className="font-mono text-xs break-all">{saved.csvSha256}</code>
          </p>
          <p className="text-xs text-muted-foreground">
            The PDF prints this digest. Whoever holds the CSV of the same service and period can check it matches
            (for example with <code className="font-mono">sha256sum</code>), so the PDF and the data agree.
          </p>
        </div>
      )}

      <p className="text-xs text-muted-foreground">
        Times in the PDF are in your time zone ({Intl.DateTimeFormat().resolvedOptions().timeZone}); the CSV keeps
        them in UTC. The PDF prints characters outside Latin-1 as “?”; the CSV keeps every character. At most 5,000
        records per export: choose a shorter period for more.
      </p>
    </div>
  );
}

/** Hand the file to the browser to save, under the name the backend gave it. */
function save(file: EvidenceFile) {
  const url = URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = file.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
