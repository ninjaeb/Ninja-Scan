import { notFound } from "next/navigation";
import { and, asc, eq } from "drizzle-orm";
import { db, businesses, services } from "@/db";
import { BookingWidget } from "./BookingWidget";

export default async function PublicBookingPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const [business] = await db
    .select()
    .from(businesses)
    .where(eq(businesses.slug, slug));
  if (!business) notFound();

  const activeServices = await db
    .select()
    .from(services)
    .where(
      and(eq(services.businessId, business.id), eq(services.active, true)),
    )
    .orderBy(asc(services.createdAt));

  return (
    <main className="mx-auto max-w-2xl p-6 py-12">
      <header className="mb-8">
        <h1 className="text-3xl font-bold">{business.name}</h1>
        {business.description ? (
          <p className="mt-2 text-slate-600">{business.description}</p>
        ) : null}
        <p className="mt-1 text-sm text-slate-400">
          All times in {business.timezone}
        </p>
      </header>

      {activeServices.length === 0 ? (
        <p className="text-slate-500">
          This business hasn&apos;t published any bookable services yet.
        </p>
      ) : (
        <BookingWidget
          slug={business.slug}
          services={activeServices.map((s) => ({
            id: s.id,
            name: s.name,
            durationMin: s.durationMin,
            priceCents: s.priceCents,
          }))}
        />
      )}

      <footer className="mt-12 border-t border-slate-200 pt-4 text-center text-xs text-slate-400">
        Powered by Bookly
      </footer>
    </main>
  );
}
