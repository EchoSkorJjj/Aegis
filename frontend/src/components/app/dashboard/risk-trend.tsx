import type { PostureSnapshot, RiskPostureDashboard } from "@/lib/types";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useIsMobile } from "@/hooks/use-mobile";

const DAY_MS = 86_400_000;
const dayNumber = (date: string) => Date.parse(`${date}T00:00:00Z`) / DAY_MS;
const dayLabel = (date: string) => new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { month: "short", day: "numeric", timeZone: "UTC" });

/** Plot only observations, at their actual dates. A missing day stays a gap, never an invented zero. */
export function RiskTrend({ data, days, onDaysChange }: { data: RiskPostureDashboard; days: number; onDaysChange: (days: number) => void }) {
  const chartWidth = useIsMobile() ? 360 : 720;
  const plotRight = chartWidth - 24;
  const plotWidth = plotRight - 48;
  const byDay = new Map(data.trend.map((point) => [point.day, point]));
  const dates = Array.from({ length: days }, (_, index) => new Date((dayNumber(data.from) + index) * DAY_MS).toISOString().slice(0, 10));
  const first = data.trend[0];
  const last = data.trend.at(-1);
  const delta = first && last ? last.openItems - first.openItems : 0;
  const maximum = Math.max(4, ...data.trend.map((point) => point.openItems));
  const ceiling = Math.ceil(maximum / 4) * 4;
  const x = (day: string) => 48 + ((dayNumber(day) - dayNumber(data.from) + 0.5) / days) * plotWidth;
  const y = (value: number) => 180 - (value / ceiling) * 152;
  const width = Math.min(32, plotWidth / days * 0.66);
  return (
    <section aria-label="Open material items trend" className="space-y-4 rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">Open material items over time</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {data.trend.length < 2 ? "Trend starts with the first observation. More recorded days will show the change."
              : `${delta === 0 ? "No change" : `${Math.abs(delta)} ${delta > 0 ? "more" : "fewer"} open items`} since ${dayLabel(first.day)} (first recorded day in this period).`}
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          Period
          <select aria-label="Trend period" value={days} onChange={(event) => onDaysChange(Number(event.target.value))}
            className="h-9 rounded-md border bg-background px-3 text-sm">
            {[7, 30, 90].map((period) => <option key={period} value={period}>Last {period} days</option>)}
          </select>
        </label>
      </div>
      <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
        <Legend color="bg-red-600" label="Critical" /><Legend color="bg-amber-600" label="High" /><Legend color="bg-slate-500" label="Other material" />
      </div>
      <svg viewBox={`0 0 ${chartWidth} 215`} role="img" aria-label={`${days}-day trend: ${data.trend.length} recorded days; missing days have no bar.`} className="w-full">
        {[0, 1, 2, 3, 4].map((tick) => {
          const count = ceiling * tick / 4;
          return <g key={tick}>
            <line x1="48" x2={plotRight} y1={y(count)} y2={y(count)} stroke="var(--border)" />
            <text x="38" y={y(count) + 4} textAnchor="end" fontSize="11" fill="var(--muted-foreground)">{count}</text>
          </g>;
        })}
        {data.trend.map((point) => {
          const other = point.openItems - point.criticalItems - point.highItems;
          return <g key={point.day}>
            <title>{`${point.day}: ${point.openItems} open (${point.criticalItems} critical, ${point.highItems} high)`}</title>
            <rect x={x(point.day) - width / 2} y={y(point.criticalItems)} width={width} height={y(0) - y(point.criticalItems)} fill="#dc2626" />
            <rect x={x(point.day) - width / 2} y={y(point.criticalItems + point.highItems)} width={width} height={y(0) - y(point.highItems)} fill="#d97706" />
            <rect x={x(point.day) - width / 2} y={y(point.openItems)} width={width} height={y(0) - y(other)} fill="#64748b" />
            {point.openItems === 0 && <circle cx={x(point.day)} cy={y(0)} r="3" fill="#64748b" />}
          </g>;
        })}
        <text x="48" y="205" fontSize="11" fill="var(--muted-foreground)">{dayLabel(data.from)}</text>
        <text x={plotRight} y="205" textAnchor="end" fontSize="11" fill="var(--muted-foreground)">{dayLabel(data.to)}</text>
      </svg>
      <p className="text-xs leading-relaxed text-muted-foreground">
        {data.trend.length} of {days} days recorded. Latest observation per UTC day; today is partial. Missing days are unknown, not zero.
        Changes can reflect new advisories, estate updates or response decisions.
      </p>
      <details className="text-sm">
        <summary className="w-fit cursor-pointer font-medium underline-offset-4 hover:underline">Daily counts</summary>
        <Table className="mt-3">
          <TableHeader><TableRow><TableHead>Date (UTC)</TableHead><TableHead>Open</TableHead><TableHead>Critical</TableHead><TableHead>High</TableHead></TableRow></TableHeader>
          <TableBody>{dates.toReversed().map((date) => <DayRow key={date} date={date} point={byDay.get(date)} />)}</TableBody>
        </Table>
      </details>
    </section>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return <span className="inline-flex items-center gap-1.5"><span aria-hidden="true" className={`size-2.5 rounded-sm ${color}`} />{label}</span>;
}

function DayRow({ date, point }: { date: string; point?: PostureSnapshot }) {
  return <TableRow><TableCell>{date}</TableCell>{point ? <>
    <TableCell>{point.openItems}</TableCell><TableCell>{point.criticalItems}</TableCell><TableCell>{point.highItems}</TableCell>
  </> : <TableCell colSpan={3} className="text-muted-foreground">Not recorded</TableCell>}</TableRow>;
}
