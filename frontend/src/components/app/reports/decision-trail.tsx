"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";

import { DataPagination } from "@/components/app/data-pagination";
import { DateRangePicker } from "@/components/app/date-range-picker";
import { ServicePicker } from "@/components/app/reports/service-picker";
import { StatusBadge, formatDateTime, type ResponseTone } from "@/components/app/responses/response-status";
import { FilterSelect, ImpactBadge, MessageRow } from "@/components/app/responses/upgrade-recommendations";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiError, auditApi, directoryApi, inventoryApi } from "@/lib/api";
import { canReadDecisionTrail } from "@/lib/roles";
import type { DecisionAction, DecisionRecord, DecisionSubject, Page, RemovedService } from "@/lib/types";
import { useAuth } from "@/providers/auth-provider";

const PAGE_SIZE = 25;
const COLUMNS = 7;

/** Each step as the trail names it, and how it reads at a glance. */
export const DECISION_ACTION: Record<DecisionAction, { label: string; tone: ResponseTone }> = {
  DRAFTED: { label: "Drafted", tone: "waiting" },
  EDITED: { label: "Edited", tone: "waiting" },
  OVERRIDDEN: { label: "Overridden", tone: "working" },
  OVERRIDE_WITHDRAWN: { label: "Override withdrawn", tone: "working" },
  APPROVED: { label: "Approved", tone: "working" },
  FILED: { label: "Filed", tone: "done" },
  FILING_FAILED: { label: "Filing failed", tone: "failed" },
  OPENED: { label: "PR opened", tone: "done" },
  OPENING_FAILED: { label: "PR opening failed", tone: "failed" },
  DISMISSED: { label: "Dismissed", tone: "declined" },
};

export const DECISION_SUBJECT: Record<DecisionSubject, string> = {
  RISK_TICKET: "Risk ticket",
  UPGRADE_PULL_REQUEST: "Upgrade pull request",
  REGULATOR_MEMO: "Regulator memo",
};

interface Filters {
  serviceId: string;
  actorId: string;
  action: string;
  subject: string;
  /** Whole days, "yyyy-MM-dd", in the reader's time zone. */
  from?: string;
  to?: string;
  newestFirst: boolean;
  page: number;
}

const NO_FILTERS: Filters = { serviceId: "", actorId: "", action: "", subject: "", newestFirst: true, page: 0 };

/** The start of a "yyyy-MM-dd" day in the reader's time zone, `days` later, as an ISO instant. */
export function startOfDay(day: string, days = 0): string {
  const [year, month, date] = day.split("-").map(Number);
  return new Date(year, month - 1, date + days).toISOString();
}

/** The response a record belongs to, on its own page. */
function responseHref(record: DecisionRecord): string {
  switch (record.subject) {
    case "RISK_TICKET":
      return `/responses/tickets/${record.subjectId}`;
    case "UPGRADE_PULL_REQUEST":
      return `/responses/upgrades/${record.subjectId}`;
    case "REGULATOR_MEMO":
      return `/responses/memos/${record.subjectId}`;
  }
}

/**
 * The audit log (E10 story 2): the decision trail searched by service, by the person who took each step, by the step
 * and what it was taken on, and by a period, so a compliance admin can answer an auditor's question ("who approved
 * this, and when?", "what did this person decide last quarter?") from one page. Each record opens to the response as
 * it stood then. Nothing here changes the trail; aegis-core refuses any change.
 */
export function DecisionTrail() {
  const { user, isLoading } = useAuth();
  if (isLoading) return <Skeleton className="h-40 w-full" />;
  if (!canReadDecisionTrail(user?.roles ?? [])) {
    return (
      <div className="mx-auto w-full max-w-6xl rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">
        You don&apos;t have access to the audit log — it&apos;s for analysts and compliance admins.
      </div>
    );
  }
  return (
    <div className="mx-auto w-full max-w-6xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Audit log</h1>
        <p className="text-sm text-muted-foreground">
          Every step a response took (drafted, edited, overridden, approved, filed or opened, dismissed), with who
          took it, when and why. Search by service, person, decision and date, then open a record to see the
          response as it stood at that moment.
        </p>
        <p className="mt-1 text-xs text-muted-foreground">
          Records are written as each decision is made and can&apos;t be changed or removed. A regulator memo is in
          the log of every service it covers. Responses decided before the trail existed have no records.
        </p>
      </div>
      {/* Another workspace starts over: its filters may name services and people this one doesn't have. */}
      <TrailSearch key={`${user?.orgId}:${user?.id}`} userId={user?.id} />
    </div>
  );
}

function TrailSearch({ userId }: { userId: string | undefined }) {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [data, setData] = useState<Page<DecisionRecord> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<"forbidden" | "invalid" | "error" | null>(null);
  const [refresh, setRefresh] = useState(0);
  // Services removed from the inventory whose decisions are on record: offered in the picker, and their names.
  const [removed, setRemoved] = useState<RemovedService[] | null>(null);
  // Each service's name by id; null once the inventory says it's gone and no record named it.
  const [serviceNames, setServiceNames] = useState<Map<string, string | null>>(new Map());
  const lookedUp = useRef(new Set<string>());
  // The service filtered by, named as the row it was picked from named it.
  const [pickedService, setPickedService] = useState<{ id: string; name: string } | null>(null);
  const [people, setPeople] = useState<Map<string, string>>(new Map());
  const [open, setOpen] = useState<DecisionRecord | null>(null);
  const filterId = useId();

  // Who's who: every member (not one page of them, which would leave the rest looking like former members), and the
  // removed services. Names are a nicety; the search works without them.
  useEffect(() => {
    let cancelled = false;
    auditApi
      .removedServices()
      .then((services) => {
        if (cancelled) return;
        setRemoved(services);
        setServiceNames((known) => new Map([...known, ...services.map((s) => [s.id, s.name] as const)]));
      })
      .catch(() => {
        if (!cancelled) setRemoved([]);
      });
    directoryApi
      .listAllMembers()
      .then((members) => {
        if (!cancelled) setPeople(new Map(members.map((member) => [member.id, member.displayName || member.email])));
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a new request for the new filters
    setLoading(true);
    setError(null);
    auditApi
      .search({
        serviceId: filters.serviceId || undefined,
        actorId: filters.actorId || undefined,
        action: filters.action ? [filters.action as DecisionAction] : undefined,
        subject: filters.subject ? [filters.subject as DecisionSubject] : undefined,
        // Whole days: from the first day's start up to (not including) the start of the day after the last.
        from: filters.from ? startOfDay(filters.from) : undefined,
        to: filters.to ? startOfDay(filters.to, 1) : filters.from ? startOfDay(filters.from, 1) : undefined,
        newestFirst: filters.newestFirst,
        page: filters.page,
        size: PAGE_SIZE,
      })
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof ApiError && err.status === 403 ? "forbidden"
          : err instanceof ApiError && err.status === 400 ? "invalid" : "error");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filters, refresh]);

  // A regulator memo names no single service, only the ids it covers: name each from the inventory, asked once per
  // service; a 404 means it has since been removed (and the removed list didn't name it either).
  useEffect(() => {
    if (removed === null || !data) return;
    const missing = [...new Set(data.content.filter((record) => !record.serviceName).flatMap((r) => r.serviceIds))]
      .filter((id) => !serviceNames.has(id) && !lookedUp.current.has(id));
    for (const id of missing) {
      lookedUp.current.add(id);
      inventoryApi
        .getService(id)
        .then((service) => setServiceNames((known) => new Map(known).set(id, service.name)))
        .catch((err) => {
          if (err instanceof ApiError && err.status === 404) {
            setServiceNames((known) => new Map(known).set(id, null));
          }
        });
    }
  }, [data, removed, serviceNames]);

  // Only members act on an organization's responses, and every member is listed: anyone else has left it.
  const nameOf = useMemo(
    () => (id: string) => {
      const name = people.get(id);
      if (id === userId) return name ? `${name} (you)` : "You";
      return name ?? `Former member (${id.slice(0, 8)})`;
    },
    [people, userId],
  );

  const serviceNameOf = (record: DecisionRecord, id: string): string => {
    if (id === record.serviceId && record.serviceName) return record.serviceName;
    const known = serviceNames.get(id);
    if (known) return known;
    return known === null ? "A removed service" : "…";
  };

  const filtering =
    filters.serviceId !== "" || filters.actorId !== "" || filters.action !== "" || filters.subject !== "" ||
    Boolean(filters.from || filters.to);
  const filter = (change: Partial<Filters>) => setFilters((current) => ({ ...current, ...change, page: 0 }));
  // Keep a person picked from a row selectable even when the directory didn't list them (they left the org).
  const peopleOptions = [...people.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  if (filters.actorId && !people.has(filters.actorId)) peopleOptions.push([filters.actorId, nameOf(filters.actorId)]);

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <ServicePicker
          id={`${filterId}-service`}
          label="Service"
          value={filters.serviceId}
          emptyLabel="All services"
          removed={removed}
          valueName={pickedService?.id === filters.serviceId ? pickedService.name : undefined}
          onChange={(serviceId) => filter({ serviceId })}
        />
        <FilterSelect
          id={`${filterId}-person`}
          label="Decided by"
          value={filters.actorId}
          allLabel="Anyone"
          onChange={(actorId) => filter({ actorId })}
        >
          {peopleOptions.map(([id, name]) => (
            <option key={id} value={id}>
              {name}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          id={`${filterId}-action`}
          label="Decision"
          value={filters.action}
          allLabel="Any decision"
          onChange={(action) => filter({ action })}
        >
          {(Object.keys(DECISION_ACTION) as DecisionAction[]).map((action) => (
            <option key={action} value={action}>
              {DECISION_ACTION[action].label}
            </option>
          ))}
        </FilterSelect>
        <FilterSelect
          id={`${filterId}-subject`}
          label="Response"
          value={filters.subject}
          allLabel="Any response"
          onChange={(subject) => filter({ subject })}
        >
          {(Object.keys(DECISION_SUBJECT) as DecisionSubject[]).map((subject) => (
            <option key={subject} value={subject}>
              {DECISION_SUBJECT[subject]}
            </option>
          ))}
        </FilterSelect>
        <DateRangePicker label="Date" from={filters.from} to={filters.to} onChange={(range) => filter(range)} />
        <div className="w-full sm:w-36">
          <label htmlFor={`${filterId}-order`} className="mb-1 block text-xs font-medium text-muted-foreground">
            Order
          </label>
          <select
            id={`${filterId}-order`}
            className="h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
            value={filters.newestFirst ? "newest" : "oldest"}
            onChange={(event) => filter({ newestFirst: event.target.value === "newest" })}
          >
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
          </select>
        </div>
        {filtering && (
          <Button variant="ghost" onClick={() => setFilters({ ...NO_FILTERS, newestFirst: filters.newestFirst })}>
            Clear
          </Button>
        )}
        <Button variant="outline" disabled={loading} onClick={() => setRefresh((count) => count + 1)}>
          Refresh
        </Button>
      </div>

      <section aria-label="Decision records" aria-busy={loading} className="space-y-3">
        {!loading && !error && data && (
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {data.totalElements === 1 ? "1 decision record" : `${data.totalElements} decision records`}
            {filtering ? (data.totalElements === 1 ? " matches" : " match") : " in total"}
          </p>
        )}
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>When</TableHead>
                <TableHead>Decision</TableHead>
                <TableHead>By</TableHead>
                <TableHead>Service</TableHead>
                <TableHead>Change</TableHead>
                <TableHead>Impact then</TableHead>
                <TableHead className="text-right">
                  <span className="sr-only">Record</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading ? (
                Array.from({ length: 3 }).map((_, row) => (
                  <TableRow key={row}>
                    {Array.from({ length: COLUMNS }).map((__, cell) => (
                      <TableCell key={cell}>
                        <Skeleton className="h-4 w-24" />
                      </TableCell>
                    ))}
                  </TableRow>
                ))
              ) : error ? (
                <MessageRow columns={COLUMNS}>
                  {error === "forbidden"
                    ? "You don't have access to the audit log — it's for analysts and compliance admins."
                    : error === "invalid"
                      ? "That search can't be answered: check the dates."
                      : "Could not load the audit log. Please try again."}
                </MessageRow>
              ) : data && data.content.length > 0 ? (
                data.content.map((record) => (
                  <TrailRow
                    key={record.id}
                    record={record}
                    nameOf={nameOf}
                    serviceNameOf={serviceNameOf}
                    onPerson={(actorId) => filter({ actorId })}
                    onService={(serviceId, name) => {
                      setPickedService({ id: serviceId, name });
                      filter({ serviceId });
                    }}
                    onOpen={() => setOpen(record)}
                  />
                ))
              ) : (
                <MessageRow columns={COLUMNS}>
                  {filtering
                    ? "No decision matches this search."
                    : "No decisions yet. Each response's steps are recorded here as they are taken."}
                </MessageRow>
              )}
            </TableBody>
          </Table>
        </div>
        {!loading && !error && data && data.totalElements > 0 && (
          <DataPagination
            page={data.number}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            pageSize={data.size}
            itemsOnPage={data.content.length}
            onPageChange={(page) => setFilters((current) => ({ ...current, page }))}
          />
        )}
      </section>

      <Dialog
        open={open !== null}
        onOpenChange={(isOpen) => {
          if (!isOpen) setOpen(null);
        }}
      >
        <DialogContent className="sm:max-w-2xl">
          {open && <RecordDetail key={open.id} listed={open} nameOf={nameOf} serviceNameOf={serviceNameOf} />}
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** A service's name on a record: the copy a ticket's record keeps, else the inventory's (a memo names none). */
type ServiceNameOf = (record: DecisionRecord, serviceId: string) => string;

function TrailRow({
  record,
  nameOf,
  serviceNameOf,
  onPerson,
  onService,
  onOpen,
}: {
  record: DecisionRecord;
  nameOf: (id: string) => string;
  serviceNameOf: ServiceNameOf;
  onPerson: (actorId: string) => void;
  onService: (serviceId: string, name: string) => void;
  onOpen: () => void;
}) {
  // A ticket's or pull request's one service; a memo's first, then how many more it covers.
  const first = record.serviceId ?? record.serviceIds[0];
  const others = record.serviceIds.length - 1;
  return (
    <TableRow>
      <TableCell className="align-top whitespace-nowrap">{formatDateTime(record.occurredAt)}</TableCell>
      <TableCell className="align-top">
        <StatusBadge tone={DECISION_ACTION[record.action].tone}>{DECISION_ACTION[record.action].label}</StatusBadge>
        <span className="mt-1 block text-xs text-muted-foreground">{DECISION_SUBJECT[record.subject]}</span>
      </TableCell>
      <TableCell className="max-w-40 align-top whitespace-normal">
        <button
          type="button"
          className="text-left underline-offset-4 hover:underline"
          title="Show only this person's decisions"
          onClick={() => onPerson(record.actorId)}
        >
          {nameOf(record.actorId)}
        </button>
      </TableCell>
      <TableCell className="max-w-48 align-top whitespace-normal">
        {first && (
          <button
            type="button"
            className="text-left underline-offset-4 hover:underline"
            title="Show only this service's decisions"
            onClick={() => onService(first, serviceNameOf(record, first))}
          >
            {serviceNameOf(record, first)}
          </button>
        )}
        {others > 0 && (
          <span className="mt-0.5 block text-xs text-muted-foreground">
            and {others} other service{others === 1 ? "" : "s"}
          </span>
        )}
      </TableCell>
      <TableCell className="max-w-sm align-top whitespace-normal">
        <span className="line-clamp-2">{record.changeTitle}</span>
        {record.reason && (
          <span className="mt-0.5 line-clamp-2 block text-xs text-muted-foreground">Why: {record.reason}</span>
        )}
      </TableCell>
      <TableCell className="align-top">
        {record.score !== null && record.tier ? (
          <ImpactBadge score={record.score} tier={record.tier} />
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      <TableCell className="text-right align-top">
        <Button variant="outline" size="sm" onClick={onOpen}>
          View
        </Button>
      </TableCell>
    </TableRow>
  );
}

/** One record in full: what the listing shows, then the response as it stood at that moment. */
function RecordDetail({
  listed,
  nameOf,
  serviceNameOf,
}: {
  listed: DecisionRecord;
  nameOf: (id: string) => string;
  serviceNameOf: ServiceNameOf;
}) {
  const [record, setRecord] = useState<DecisionRecord | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    auditApi
      .get(listed.id)
      .then((result) => {
        if (!cancelled) setRecord(result);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [listed.id]);

  const shown = record ?? listed;
  return (
    <>
      <DialogHeader>
        <DialogTitle>
          {DECISION_ACTION[shown.action].label} · {DECISION_SUBJECT[shown.subject]}
        </DialogTitle>
        <DialogDescription>
          Record #{shown.sequence}, {formatDateTime(shown.occurredAt)}, by {nameOf(shown.actorId)}.
        </DialogDescription>
      </DialogHeader>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-2 text-sm">
        <dt className="text-muted-foreground">Change</dt>
        <dd className="break-words">
          <Link href={`/findings/${shown.changeSignalId}`} className="underline-offset-4 hover:underline">
            {shown.changeTitle}
          </Link>
        </dd>
        <dt className="text-muted-foreground">{shown.serviceIds.length > 1 ? "Services" : "Service"}</dt>
        <dd>
          {shown.serviceIds.map((id) => serviceNameOf(shown, id)).join(", ") || "—"}
          {shown.subject === "REGULATOR_MEMO" && (
            <span className="block text-xs text-muted-foreground">
              A regulator memo covers every service the change affects; it&apos;s in each one&apos;s log.
            </span>
          )}
        </dd>
        <dt className="text-muted-foreground">Impact then</dt>
        <dd>{shown.score !== null && shown.tier ? <ImpactBadge score={shown.score} tier={shown.tier} /> : "—"}</dd>
        <dt className="text-muted-foreground">Why</dt>
        <dd className="whitespace-pre-wrap break-words">{shown.reason ?? "—"}</dd>
        <dt className="text-muted-foreground">Response</dt>
        <dd>
          <Link href={responseHref(shown)} className="underline-offset-4 hover:underline">
            Open the response as it is now
          </Link>
        </dd>
      </dl>
      <div className="space-y-2">
        <h3 className="text-sm font-medium">The response as it stood then</h3>
        {failed ? (
          <p className="text-sm text-muted-foreground">Could not load this record&apos;s snapshot. Please try again.</p>
        ) : record ? (
          <pre className="max-h-80 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-xs whitespace-pre-wrap break-words">
            {JSON.stringify(record.state ?? null, null, 2)}
          </pre>
        ) : (
          <Skeleton className="h-40 w-full" />
        )}
      </div>
    </>
  );
}
