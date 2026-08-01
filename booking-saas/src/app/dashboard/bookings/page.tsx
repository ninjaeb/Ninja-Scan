import Link from "next/link";
import { and, asc, desc, eq, gte, lt } from "drizzle-orm";
import { db, bookings, services } from "@/db";
import { confirmBooking, cancelBooking } from "@/app/actions/bookings";
import { formatInTz } from "@/lib/bookings";
import { requireOwner } from "@/lib/tenant";
import { Badge, Card, EmptyState } from "@/components/ui";

export const metadata = { title: "Bookings — Bookly" };

const TONES = { pending: "amber", confirmed: "green", cancelled: "red" } as const;

export default async function BookingsPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string }>;
}) {
  const { business } = await requireOwner();
  const { tab } = await searchParams;
  const showPast = tab === "past";
  const nowIso = new Date().toISOString();

  const rows = await db
    .select({
      id: bookings.id,
      customerName: bookings.customerName,
      customerEmail: bookings.customerEmail,
      customerPhone: bookings.customerPhone,
      startUtc: bookings.startUtc,
      status: bookings.status,
      serviceName: services.name,
    })
    .from(bookings)
    .innerJoin(services, eq(bookings.serviceId, services.id))
    .where(
      and(
        eq(bookings.businessId, business.id),
        showPast
          ? lt(bookings.startUtc, nowIso)
          : gte(bookings.startUtc, nowIso),
      ),
    )
    .orderBy(showPast ? desc(bookings.startUtc) : asc(bookings.startUtc))
    .limit(100);

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-6 text-2xl font-bold">Bookings</h1>
      <div className="mb-4 flex gap-2">
        <Link
          href="/dashboard/bookings"
          className={`rounded-lg px-3 py-1.5 text-sm font-medium ${!showPast ? "bg-indigo-600 text-white" : "text-slate-600 hover:bg-slate-100"}`}
        >
          Upcoming
        </Link>
        <Link
          href="/dashboard/bookings?tab=past"
          className={`rounded-lg px-3 py-1.5 text-sm font-medium ${showPast ? "bg-indigo-600 text-white" : "text-slate-600 hover:bg-slate-100"}`}
        >
          Past
        </Link>
      </div>

      {rows.length === 0 ? (
        <EmptyState
          title={showPast ? "No past bookings" : "No upcoming bookings"}
          hint={
            showPast
              ? undefined
              : `Share your booking page: /b/${business.slug}`
          }
        />
      ) : (
        <Card className="divide-y divide-slate-100 p-0">
          {rows.map((b) => (
            <div
              key={b.id}
              className="flex items-center justify-between gap-4 p-4"
              data-testid={`booking-${b.customerEmail}`}
            >
              <div className="min-w-0">
                <p className="font-medium">
                  {b.customerName}{" "}
                  <Badge tone={TONES[b.status]}>{b.status}</Badge>
                </p>
                <p className="truncate text-sm text-slate-500">
                  {b.serviceName} · {formatInTz(b.startUtc, business.timezone)}
                </p>
                <p className="truncate text-xs text-slate-400">
                  {b.customerEmail}
                  {b.customerPhone ? ` · ${b.customerPhone}` : ""}
                </p>
              </div>
              {b.status !== "cancelled" && !showPast && (
                <div className="flex shrink-0 gap-2">
                  {b.status === "pending" && (
                    <form action={confirmBooking}>
                      <input type="hidden" name="id" value={b.id} />
                      <button
                        type="submit"
                        className="rounded-lg bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700"
                      >
                        Confirm
                      </button>
                    </form>
                  )}
                  <form action={cancelBooking}>
                    <input type="hidden" name="id" value={b.id} />
                    <button
                      type="submit"
                      className="rounded-lg border border-red-200 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50"
                    >
                      Cancel
                    </button>
                  </form>
                </div>
              )}
            </div>
          ))}
        </Card>
      )}
    </div>
  );
}
