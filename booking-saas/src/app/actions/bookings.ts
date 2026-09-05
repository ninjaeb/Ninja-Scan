"use server";

import { and, eq } from "drizzle-orm";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { db, bookings, businesses, services, users } from "@/db";
import { createBooking, formatInTz } from "@/lib/bookings";
import { mailer } from "@/lib/mailer";
import { requireOwner } from "@/lib/tenant";
import { publicBookingSchema } from "@/lib/validation";
import type { FormState } from "./auth";

export async function createPublicBooking(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const slug = String(formData.get("slug") ?? "");
  const parsed = publicBookingSchema.safeParse({
    serviceId: formData.get("serviceId"),
    startUtc: formData.get("startUtc"),
    customerName: formData.get("customerName"),
    customerEmail: formData.get("customerEmail"),
    customerPhone: formData.get("customerPhone") ?? "",
  });
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }

  const [business] = await db
    .select()
    .from(businesses)
    .where(eq(businesses.slug, slug));
  if (!business) return { error: "Business not found" };

  const [service] = await db
    .select()
    .from(services)
    .where(
      and(
        eq(services.id, parsed.data.serviceId),
        eq(services.businessId, business.id),
        eq(services.active, true),
      ),
    );
  if (!service) return { error: "Service not found" };

  const result = await createBooking({
    business,
    service,
    startUtc: parsed.data.startUtc,
    customerName: parsed.data.customerName,
    customerEmail: parsed.data.customerEmail,
    customerPhone: parsed.data.customerPhone,
  });
  if (!result.ok) return { error: result.error };

  const when = formatInTz(result.booking.startUtc, business.timezone);
  const [owner] = await db
    .select({ email: users.email })
    .from(users)
    .where(eq(users.id, business.ownerId));
  if (owner) {
    await mailer.send({
      to: owner.email,
      subject: `New booking — ${service.name} on ${when}`,
      body: `${parsed.data.customerName} (${parsed.data.customerEmail}) booked ${service.name} on ${when} (${business.timezone}).\n\nConfirm or cancel it from your Bookly dashboard.`,
    });
  }
  await mailer.send({
    to: parsed.data.customerEmail,
    subject: `Booking received — ${service.name} at ${business.name}`,
    body: `Hi ${parsed.data.customerName},\n\nWe received your booking for ${service.name} on ${when} (${business.timezone}).\nYou'll get a confirmation once ${business.name} approves it.\n\n— Bookly`,
  });

  redirect(`/b/${slug}/confirmed/${result.booking.id}`);
}

export async function confirmBooking(formData: FormData): Promise<void> {
  const { business } = await requireOwner();
  const id = String(formData.get("id") ?? "");

  const [row] = await db
    .select()
    .from(bookings)
    .where(and(eq(bookings.id, id), eq(bookings.businessId, business.id)));
  if (!row || row.status !== "pending") return;

  await db
    .update(bookings)
    .set({ status: "confirmed" })
    .where(and(eq(bookings.id, id), eq(bookings.businessId, business.id)));

  await mailer.send({
    to: row.customerEmail,
    subject: `Confirmed — your booking at ${business.name}`,
    body: `Hi ${row.customerName},\n\nYour booking on ${formatInTz(row.startUtc, business.timezone)} (${business.timezone}) is confirmed.\n\nSee you soon!\n— ${business.name} via Bookly`,
  });
  revalidatePath("/dashboard/bookings");
  revalidatePath("/dashboard");
}

export async function cancelBooking(formData: FormData): Promise<void> {
  const { business } = await requireOwner();
  const id = String(formData.get("id") ?? "");

  const [row] = await db
    .select()
    .from(bookings)
    .where(and(eq(bookings.id, id), eq(bookings.businessId, business.id)));
  if (!row || row.status === "cancelled") return;

  await db
    .update(bookings)
    .set({ status: "cancelled" })
    .where(and(eq(bookings.id, id), eq(bookings.businessId, business.id)));

  await mailer.send({
    to: row.customerEmail,
    subject: `Cancelled — your booking at ${business.name}`,
    body: `Hi ${row.customerName},\n\nYour booking on ${formatInTz(row.startUtc, business.timezone)} (${business.timezone}) was cancelled by ${business.name}.\n\n— Bookly`,
  });
  revalidatePath("/dashboard/bookings");
  revalidatePath("/dashboard");
}
