import Link from "next/link";
import { and, asc, count, eq, gte, ne } from "drizzle-orm";
import { db, bookings, services } from "@/db";
import { formatInTz } from "@/lib/bookings";
import { requireOwner } from "@/lib/tenant";
import { Badge, Card, EmptyState } from "@/components/ui";

export const metadata = { title: "Dashboard — Bookly" };

export default async function DashboardPage() {
  const { business } = await requireOwner();
  const nowIso = new Date().toISOString();

  const [upcoming, [serviceCount], [upcomingCount]] = await Promise.all([
    db
      .select({
        id: bookings.id,
        customerName: bookings.customerName,
        startUtc: bookings.startUtc,
        status: bookings.status,
        serviceName: services.name,
      })
      .from(bookings)
      .innerJoin(services, eq(bookings.serviceId, services.id))
      .where(
        and(
          eq(bookings.businessId, business.id),
          ne(bookings.status, "cancelled"),
          gte(bookings.startUtc, nowIso),
        ),
      )
      .orderBy(asc(bookings.startUtc))
      .limit(5),
    db
      .select({ n: count() })
      .from(services)
      .where(and(eq(services.businessId, business.id), eq(services.active, true))),
    db
      .select({ n: count() })
      .from(bookings)
      .where(
        and(
          eq(bookings.businessId, business.id),
          ne(bookings.status, "cancelled"),
          gte(bookings.startUtc, nowIso),
        ),
      ),
  ]);

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-6 text-2xl font-bold">Overview</h1>
      <div className="mb-8 grid grid-cols-2 gap-4">
        <Card>
          <p className="text-sm text-slate-500">Upcoming bookings</p>
          <p className="text-3xl font-bold">{upcomingCount.n}</p>
        </Card>
        <Card>
          <p className="text-sm text-slate-500">Active services</p>
          <p className="text-3xl font-bold">{serviceCount.n}</p>
        </Card>
      </div>

      <h2 className="mb-3 text-lg font-semibold">Next up</h2>
      {upcoming.length === 0 ? (
        <EmptyState
          title="No upcoming bookings"
          hint={`Share your booking page: /b/${business.slug}`}
        />
      ) : (
        <Card className="divide-y divide-slate-100 p-0">
          {upcoming.map((b) => (
            <div key={b.id} className="flex items-center justify-between p-4">
              <div>
                <p className="font-medium">{b.customerName}</p>
                <p className="text-sm text-slate-500">
                  {b.serviceName} · {formatInTz(b.startUtc, business.timezone)}
                </p>
              </div>
              <Badge tone={b.status === "confirmed" ? "green" : "amber"}>
                {b.status}
              </Badge>
            </div>
          ))}
        </Card>
      )}
      <p className="mt-4 text-sm">
        <Link href="/dashboard/bookings" className="text-indigo-600">
          View all bookings →
        </Link>
      </p>
    </div>
  );
}
