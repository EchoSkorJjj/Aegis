"use client";

import * as React from "react";
import { format, parse } from "date-fns";
import { CalendarIcon } from "lucide-react";
import type { DateRange } from "react-day-picker";

import { Button } from "@/components/ui/button";
import { Calendar } from "@/components/ui/calendar";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

const ISO = "yyyy-MM-dd";

/** Parse a "yyyy-MM-dd" string to a local Date (no timezone shift), or undefined. */
function toDate(value?: string): Date | undefined {
  if (!value) return undefined;
  const parsed = parse(value, ISO, new Date());
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

function toIso(date?: Date): string | undefined {
  return date ? format(date, ISO) : undefined;
}

export type DateRangeValue = { from?: string; to?: string };

type DateRangePickerProps = {
  /** Field label shown above the trigger. */
  label: string;
  /** Current bounds as "yyyy-MM-dd" strings (what the list filters expect). */
  from?: string;
  to?: string;
  onChange: (range: DateRangeValue) => void;
  align?: "start" | "center" | "end";
  className?: string;
};

/**
 * A single range control that replaces a pair of native date inputs. Values are "yyyy-MM-dd" strings
 * so callers keep the same filter contract; the popover holds a shadcn calendar in range mode.
 */
export function DateRangePicker({
  label,
  from,
  to,
  onChange,
  align = "start",
  className,
}: DateRangePickerProps) {
  const selected: DateRange | undefined = React.useMemo(() => {
    const start = toDate(from);
    const end = toDate(to);
    return start || end ? { from: start, to: end } : undefined;
  }, [from, to]);

  const hasValue = Boolean(from || to);
  const display = selected?.from
    ? selected.to
      ? `${format(selected.from, "MMM d, yyyy")} – ${format(selected.to, "MMM d, yyyy")}`
      : format(selected.from, "MMM d, yyyy")
    : "Any date";

  return (
    <div className={cn("w-full sm:w-auto", className)}>
      <label className="mb-1 block text-xs font-medium text-muted-foreground">{label}</label>
      <Popover>
        <PopoverTrigger
          render={
            <Button
              variant="outline"
              className={cn(
                "h-9 w-full justify-start px-3 font-normal sm:w-64",
                !hasValue && "text-muted-foreground",
              )}
            >
              <CalendarIcon data-icon="inline-start" />
              <span className="truncate">{display}</span>
            </Button>
          }
        />
        <PopoverContent className="w-auto p-0" align={align}>
          <Calendar
            mode="range"
            defaultMonth={selected?.from}
            selected={selected}
            onSelect={(range) => onChange({ from: toIso(range?.from), to: toIso(range?.to) })}
            numberOfMonths={2}
            autoFocus
          />
          {hasValue && (
            <div className="border-t p-2">
              <Button
                variant="ghost"
                size="sm"
                className="w-full"
                onClick={() => onChange({ from: undefined, to: undefined })}
              >
                Clear
              </Button>
            </div>
          )}
        </PopoverContent>
      </Popover>
    </div>
  );
}
