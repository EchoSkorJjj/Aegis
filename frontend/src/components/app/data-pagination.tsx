"use client";

import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

type DataPaginationProps = {
  /** 0-indexed current page — Spring's `data.number`. */
  page: number;
  /** Total number of pages — `data.totalPages`. */
  totalPages: number;
  /** Total rows across all pages — `data.totalElements`. */
  totalElements: number;
  /** Page size — `data.size`. */
  pageSize: number;
  /** Rows on the current page — `data.content.length`. */
  itemsOnPage: number;
  onPageChange: (page: number) => void;
  /** How many page numbers to show either side of the current one (default 1). */
  siblingCount?: number;
};

const ELLIPSIS = "ellipsis" as const;

/**
 * The page numbers to render: always the first and last page and the current page with `siblings`
 * neighbours, and an ellipsis wherever there is a gap — e.g. `1 … 5 6 7 … 20`. When everything fits
 * (few enough pages) it returns every page with no ellipsis.
 */
function pageItems(current: number, total: number, siblings: number): (number | typeof ELLIPSIS)[] {
  const slots = siblings * 2 + 5; // first + last + current + 2 siblings + 2 ellipsis slots
  if (total <= slots) {
    return Array.from({ length: total }, (_, index) => index + 1);
  }
  const left = Math.max(current - siblings, 2);
  const right = Math.min(current + siblings, total - 1);
  const items: (number | typeof ELLIPSIS)[] = [1];
  if (left > 2) items.push(ELLIPSIS);
  for (let page = left; page <= right; page++) items.push(page);
  if (right < total - 1) items.push(ELLIPSIS);
  items.push(total);
  return items;
}

/**
 * Full pager for a Spring `Page`: a "start–end of total" summary plus first / previous / numbered /
 * next / last controls. The numbered buttons collapse to a "Page X of Y" indicator on the narrowest
 * screens so the row never overflows. Pass the fields straight from the page object.
 */
export function DataPagination({
  page,
  totalPages,
  totalElements,
  pageSize,
  itemsOnPage,
  onPageChange,
  siblingCount = 1,
}: DataPaginationProps) {
  const pages = Math.max(1, totalPages);
  const current = page + 1; // 1-indexed for display
  const atFirst = page <= 0;
  const atLast = page >= pages - 1;
  const start = totalElements === 0 ? 0 : page * pageSize + 1;
  const end = page * pageSize + itemsOnPage;

  const go = (target: number) => {
    const clamped = Math.min(Math.max(target, 0), pages - 1);
    if (clamped !== page) onPageChange(clamped);
  };

  return (
    <div className="flex flex-col gap-3 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
      <span>
        {start}–{end} of {totalElements}
      </span>
      <div className="flex items-center gap-1">
        <Button variant="outline" size="icon-sm" aria-label="First page" disabled={atFirst} onClick={() => go(0)}>
          <ChevronsLeft />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          aria-label="Previous page"
          disabled={atFirst}
          onClick={() => go(page - 1)}
        >
          <ChevronLeft />
        </Button>

        {/* Numbered pages — the narrowest screens fall back to the compact indicator below. */}
        <div className="hidden items-center gap-1 sm:flex">
          {pageItems(current, pages, siblingCount).map((item, index) =>
            item === ELLIPSIS ? (
              <span key={`ellipsis-${index}`} className="select-none px-1.5" aria-hidden>
                …
              </span>
            ) : (
              <Button
                key={item}
                variant={item === current ? "default" : "ghost"}
                size="sm"
                className={cn("min-w-8 justify-center px-2", item !== current && "text-muted-foreground")}
                aria-label={`Page ${item}`}
                aria-current={item === current ? "page" : undefined}
                onClick={() => go(item - 1)}
              >
                {item}
              </Button>
            ),
          )}
        </div>

        <span className="px-2 sm:hidden">
          Page {current} of {pages}
        </span>

        <Button variant="outline" size="icon-sm" aria-label="Next page" disabled={atLast} onClick={() => go(page + 1)}>
          <ChevronRight />
        </Button>
        <Button variant="outline" size="icon-sm" aria-label="Last page" disabled={atLast} onClick={() => go(pages - 1)}>
          <ChevronsRight />
        </Button>
      </div>
    </div>
  );
}
