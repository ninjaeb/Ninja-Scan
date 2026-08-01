import { asc, eq } from "drizzle-orm";
import { db, services } from "@/db";
import { requireOwner } from "@/lib/tenant";
import { toggleService } from "@/app/actions/services";
import { ServiceForm } from "@/components/ServiceForm";
import { formatPrice } from "@/lib/bookings";
import { Badge, Card, EmptyState } from "@/components/ui";

export const metadata = { title: "Services — Bookly" };

export default async function ServicesPage() {
  const { business } = await requireOwner();
  const rows = await db
    .select()
    .from(services)
    .where(eq(services.businessId, business.id))
    .orderBy(asc(services.createdAt));

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-6 text-2xl font-bold">Services</h1>

      {rows.length === 0 ? (
        <EmptyState
          title="No services yet"
          hint="Add your first service below — customers book these."
        />
      ) : (
        <Card className="divide-y divide-slate-100 p-0">
          {rows.map((s) => (
            <div key={s.id} className="flex items-center justify-between p-4">
              <div>
                <p className="font-medium">
                  {s.name}{" "}
                  {!s.active && <Badge tone="slate">hidden</Badge>}
                </p>
                <p className="text-sm text-slate-500">
                  {s.durationMin} min · {formatPrice(s.priceCents)}
                  {s.bufferMin > 0 ? ` · ${s.bufferMin} min buffer` : ""}
                </p>
              </div>
              <form action={toggleService}>
                <input type="hidden" name="id" value={s.id} />
                <button
                  type="submit"
                  className="text-sm text-indigo-600 hover:underline"
                >
                  {s.active ? "Hide" : "Show"}
                </button>
              </form>
            </div>
          ))}
        </Card>
      )}

      <h2 className="mb-3 mt-8 text-lg font-semibold">Add a service</h2>
      <Card>
        <ServiceForm />
      </Card>
    </div>
  );
}
