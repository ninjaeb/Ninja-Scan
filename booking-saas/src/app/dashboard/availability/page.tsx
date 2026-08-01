import { asc, eq } from "drizzle-orm";
import { db, availabilityRules } from "@/db";
import { requireOwner } from "@/lib/tenant";
import { AvailabilityForm } from "@/components/AvailabilityForm";
import { Card } from "@/components/ui";

export const metadata = { title: "Availability — Bookly" };

export default async function AvailabilityPage() {
  const { business } = await requireOwner();
  const rules = await db
    .select()
    .from(availabilityRules)
    .where(eq(availabilityRules.businessId, business.id))
    .orderBy(asc(availabilityRules.weekday));

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-1 text-2xl font-bold">Weekly availability</h1>
      <p className="mb-6 text-sm text-slate-500">
        Opening hours in your business timezone ({business.timezone}). Customers
        can only book inside these windows.
      </p>
      <Card>
        <AvailabilityForm
          initial={rules.map((r) => ({
            weekday: r.weekday,
            startMin: r.startMin,
            endMin: r.endMin,
          }))}
        />
      </Card>
    </div>
  );
}
