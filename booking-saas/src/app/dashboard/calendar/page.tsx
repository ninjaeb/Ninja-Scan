import Link from "next/link";
import { DateTime } from "luxon";
import { and, eq, gte, lt, ne } from "drizzle-orm";
import { db, bookings } from "@/db";
import { requireOwner } from "@/lib/tenant";
import { Badge } from "@/components/ui";

export const metadata = { title: "Calendar — Bookly" };

export default async function CalendarPage({
  searchParams,
}: {
  searchParams: Promise<{ month?: string }>;
}) {
  const { business } = await requireOwner();
  const { month } = await searchParams;

  const base =
    month && /^\d{4}-\d{2}$/.test(month)
      ? DateTime.fromISO(`${month}-01`, { zone: business.timezone })
      : DateTime.now().setZone(business.timezone);
  const monthStart = base.startOf("month");
  const monthEnd = monthStart.plus({ months: 1 });

  const rows = await db
    .select({ startUtc: bookings.startUtc, status: bookings.status })
    .from(bookings)
    .where(
      and(
        eq(bookings.businessId, business.id),
        ne(bookings.status, "cancelled"),
        gte(bookings.startUtc, monthStart.toUTC().toISO()!),
        lt(bookings.startUtc, monthEnd.toUTC().toISO()!),
      ),
    );

  const countsByDay = new Map<string, number>();
  for (const r of rows) {
    const day = DateTime.fromISO(r.startUtc)
      .setZone(business.timezone)
      .toISODate()!;
    countsByDay.set(day, (countsByDay.get(day) ?? 0) + 1);
  }

  // Calendar grid: pad to the Monday on/before the 1st.
  const gridStart = monthStart.minus({ days: monthStart.weekday - 1 });
  const weeks: DateTime[][] = [];
  for (let d = gridStart; d < monthEnd; d = d.plus({ weeks: 1 })) {
    weeks.push(Array.from({ length: 7 }, (_, i) => d.plus({ days: i })));
  }

  const prev = monthStart.minus({ months: 1 }).toFormat("yyyy-MM");
  const next = monthStart.plus({ months: 1 }).toFormat("yyyy-MM");
  const today = DateTime.now().setZone(business.timezone).toISODate();

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">
          {monthStart.toFormat("MMMM yyyy")}
        </h1>
        <div className="flex gap-2">
          <Link
            href={`/dashboard/calendar?month=${prev}`}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100"
          >
            ← Prev
          </Link>
          <Link
            href={`/dashboard/calendar?month=${next}`}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100"
          >
            Next →
          </Link>
        </div>
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <div className="grid grid-cols-7 border-b border-slate-200 bg-slate-50 text-center text-xs font-medium text-slate-500">
          {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => (
            <div key={d} className="py-2">
              {d}
            </div>
          ))}
        </div>
        {weeks.map((week, wi) => (
          <div key={wi} className="grid grid-cols-7 divide-x divide-slate-100 border-b border-slate-100 last:border-b-0">
            {week.map((day) => {
              const iso = day.toISODate()!;
              const inMonth = day.month === monthStart.month;
              const n = countsByDay.get(iso) ?? 0;
              return (
                <div
                  key={iso}
                  className={`min-h-20 p-2 ${inMonth ? "" : "bg-slate-50 text-slate-300"} ${iso === today ? "bg-indigo-50" : ""}`}
                >
                  <span className="text-sm">{day.day}</span>
                  {n > 0 && inMonth && (
                    <div className="mt-1">
                      <Badge tone="indigo">
                        {n} booking{n > 1 ? "s" : ""}
                      </Badge>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
      <p className="mt-3 text-sm text-slate-400">
        Times shown in {business.timezone}
      </p>
    </div>
  );
}
