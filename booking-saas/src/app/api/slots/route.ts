import { NextRequest, NextResponse } from "next/server";
import { and, eq } from "drizzle-orm";
import { db, businesses, services } from "@/db";
import { getAvailableSlots } from "@/lib/bookings";
import { dateSchema } from "@/lib/validation";

export async function GET(req: NextRequest) {
  const params = req.nextUrl.searchParams;
  const slug = params.get("slug") ?? "";
  const serviceId = params.get("serviceId") ?? "";
  const date = dateSchema.safeParse(params.get("date"));
  if (!slug || !serviceId || !date.success) {
    return NextResponse.json({ error: "Bad request" }, { status: 400 });
  }

  const [business] = await db
    .select()
    .from(businesses)
    .where(eq(businesses.slug, slug));
  if (!business) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  const [service] = await db
    .select()
    .from(services)
    .where(
      and(
        eq(services.id, serviceId),
        eq(services.businessId, business.id),
        eq(services.active, true),
      ),
    );
  if (!service) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  const slots = await getAvailableSlots(business, service, date.data);
  return NextResponse.json({ slots, timezone: business.timezone });
}
