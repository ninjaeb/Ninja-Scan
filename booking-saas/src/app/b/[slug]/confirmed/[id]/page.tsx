import Link from "next/link";
import { notFound } from "next/navigation";
import { and, eq } from "drizzle-orm";
import { db, bookings, businesses, services } from "@/db";
import { formatInTz, formatPrice } from "@/lib/bookings";
import { Card } from "@/components/ui";

export const metadata = { title: "Booking received — Bookly" };

export default async function ConfirmedPage({
  params,
}: {
  params: Promise<{ slug: string; id: string }>;
}) {
  const { slug, id } = await params;

  const [business] = await db
    .select()
    .from(businesses)
    .where(eq(businesses.slug, slug));
  if (!business) notFound();

  const [booking] = await db
    .select()
    .from(bookings)
    .where(and(eq(bookings.id, id), eq(bookings.businessId, business.id)));
  if (!booking || booking.status === "cancelled") notFound();

  const [service] = await db
    .select()
    .from(services)
    .where(eq(services.id, booking.serviceId));

  return (
    <main className="mx-auto max-w-md p-6 py-16">
      <Card className="text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-green-100 text-2xl">
          ✓
        </div>
        <h1 className="text-xl font-semibold" data-testid="confirmation-title">
          Booking received!
        </h1>
        <p className="mt-2 text-slate-600">
          {service?.name} at {business.name}
        </p>
        <p className="mt-1 font-medium">
          {formatInTz(booking.startUtc, business.timezone)}
        </p>
        <p className="text-sm text-slate-400">({business.timezone})</p>
        {service && service.priceCents > 0 ? (
          <p className="mt-2 text-sm text-slate-500">
            Price: {formatPrice(service.priceCents)} — payable on site
          </p>
        ) : null}
        <p className="mt-4 text-sm text-slate-500">
          We emailed a receipt to {booking.customerEmail}. {business.name} will
          confirm your booking shortly.
        </p>
      </Card>
      <p className="mt-6 text-center text-sm">
        <Link href={`/b/${slug}`} className="text-indigo-600">
          ← Back to {business.name}
        </Link>
      </p>
    </main>
  );
}
