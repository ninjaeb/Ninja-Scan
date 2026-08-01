"use client";

import { useActionState, useState } from "react";
import { saveWeeklyAvailability } from "@/app/actions/availability";
import { Button, ErrorText, Input } from "@/components/ui";

const DAYS = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];

function toHHMM(min: number): string {
  return `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;
}

export function AvailabilityForm({
  initial,
}: {
  initial: { weekday: number; startMin: number; endMin: number }[];
}) {
  const [state, formAction, pending] = useActionState(saveWeeklyAvailability, {});
  const byDay = new Map(initial.map((r) => [r.weekday, r]));
  const [enabled, setEnabled] = useState<boolean[]>(
    DAYS.map((_, d) => byDay.has(d)),
  );

  return (
    <form action={formAction} className="space-y-3">
      {DAYS.map((day, d) => {
        const rule = byDay.get(d);
        return (
          <div key={day} className="flex items-center gap-4">
            <label className="flex w-32 items-center gap-2 text-sm font-medium">
              <input
                type="checkbox"
                name={`enabled_${d}`}
                checked={enabled[d]}
                onChange={(e) =>
                  setEnabled((prev) =>
                    prev.map((v, i) => (i === d ? e.target.checked : v)),
                  )
                }
                className="h-4 w-4 accent-indigo-600"
              />
              {day}
            </label>
            {enabled[d] ? (
              <div className="flex items-center gap-2">
                <Input
                  type="time"
                  name={`start_${d}`}
                  defaultValue={toHHMM(rule?.startMin ?? 9 * 60)}
                  required
                  className="w-32"
                />
                <span className="text-slate-400">to</span>
                <Input
                  type="time"
                  name={`end_${d}`}
                  defaultValue={toHHMM(rule?.endMin ?? 17 * 60)}
                  required
                  className="w-32"
                />
              </div>
            ) : (
              <span className="text-sm text-slate-400">Closed</span>
            )}
          </div>
        );
      })}
      <ErrorText>{state.error}</ErrorText>
      <Button type="submit" disabled={pending}>
        {pending ? "Saving…" : "Save availability"}
      </Button>
    </form>
  );
}
