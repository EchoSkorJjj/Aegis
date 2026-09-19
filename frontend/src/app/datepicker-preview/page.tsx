"use client";
// TEMPORARY preview route. Delete before committing.
import { useState } from "react";
import { DateRangePicker } from "@/components/app/date-range-picker";
export default function DatePickerPreview() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  return (
    <div className="mx-auto mt-12 w-full max-w-xl space-y-6 p-6">
      <h1 className="text-2xl font-semibold">Date range picker preview</h1>
      <DateRangePicker label="Published" from={from} to={to}
        onChange={(r) => { setFrom(r.from ?? ""); setTo(r.to ?? ""); }} />
      <pre className="rounded-md bg-muted p-3 text-sm">from: {from || "—"}{"\n"}to: {to || "—"}</pre>
    </div>
  );
}
