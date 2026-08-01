import "server-only";
import { and, eq, gt, lt, ne } from "drizzle-orm";
import { DateTime } from "luxon";
import { nanoid } from "nanoid";
import {
  db,
  availabilityRules,
  bookings,
  type Booking,
  type Business,
  type Service,
} from "@/db";
import { generateSlots, weekdayOf, type Slot } from "./slots";

/** Normalize any ISO instant to the canonical stored form (UTC, ms, Z). */
export function toUtcIso(iso: string): string | null {
  const dt = DateTime.fromISO(iso, { zone: "utc" });
  return dt.isValid ? dt.toUTC().toISO() : null;
}

/** Bookable slots for one business-local date. */
export async function getAvailableSlots(
  business: Business,
  service: Service,
  date: string,
  now: Date = new Date(),
): Promise<Slot[]> {
  const weekday = weekdayOf(date, business.timezone);
  if (weekday < 0) return [];

  const rules = await db
    .select()
    .from(availabilityRules)
    .where(
      and(
        eq(availabilityRules.businessId, business.id),
        eq(availabilityRules.weekday, weekday),
      ),
    );
  if (rules.length === 0) return [];

  // Fetch active bookings whose blocked interval could touch this local day
  // (pad one day each side to cover timezone offsets).
  const dayStart = DateTime.fromISO(date, { zone: business.timezone }).startOf(
    "day",
  );
  const from = dayStart.minus({ days: 1 }).toUTC().toISO()!;
  const to = dayStart.plus({ days: 2 }).toUTC().toISO()!;
  const existing = await db
    .select({
      startUtc: bookings.startUtc,
      blockedUntilUtc: bookings.blockedUntilUtc,
    })
    .from(bookings)
    .where(
      and(
        eq(bookings.businessId, business.id),
        ne(bookings.status, "cancelled"),
        gt(bookings.blockedUntilUtc, from),
        lt(bookings.startUtc, to),
      ),
    );

  return generateSlots({
    date,
    timezone: business.timezone,
    rules,
    durationMin: service.durationMin,
    bufferMin: service.bufferMin,
    existing,
    now,
  });
}

export type CreateBookingResult =
  | { ok: true; booking: Booking }
  | { ok: false; error: string };

/**
 * Create a booking after re-validating the slot against availability and,
 * inside a synchronous transaction, against concurrent bookings. better-sqlite3
 * runs transactions atomically on a single writer, so the overlap check and
 * insert cannot interleave with another request's.
 */
export async function createBooking(input: {
  business: Business;
  service: Service;
  startUtc: string;
  customerName: string;
  customerEmail: string;
  customerPhone: string;
  now?: Date;
}): Promise<CreateBookingResult> {
  const { business, service } = input;
  const now = input.now ?? new Date();

  const start = toUtcIso(input.startUtc);
  if (!start) return { ok: false, error: "Invalid start time" };

  const localDate = DateTime.fromISO(start)
    .setZone(business.timezone)
    .toISODate()!;
  const offered = await getAvailableSlots(business, service, localDate, now);
  if (!offered.some((s) => s.startUtc === start)) {
    return {
      ok: false,
      error: "That time is no longer available. Please pick another slot.",
    };
  }

  const startDt = DateTime.fromISO(start);
  const endUtc = startDt.plus({ minutes: service.durationMin }).toUTC().toISO()!;
  const blockedUntilUtc = startDt
    .plus({ minutes: service.durationMin + service.bufferMin })
    .toUTC()
    .toISO()!;

  const row = {
    id: nanoid(),
    businessId: business.id,
    serviceId: service.id,
    customerName: input.customerName,
    customerEmail: input.customerEmail,
    customerPhone: input.customerPhone,
    startUtc: start,
    endUtc,
    blockedUntilUtc,
    status: "pending" as const,
    createdAt: now.toISOString(),
  };

  try {
    const created = db.transaction((tx) => {
      const conflicts = tx
        .select({ id: bookings.id })
        .from(bookings)
        .where(
          and(
            eq(bookings.businessId, business.id),
            ne(bookings.status, "cancelled"),
            lt(bookings.startUtc, blockedUntilUtc),
            gt(bookings.blockedUntilUtc, start),
          ),
        )
        .all();
      if (conflicts.length > 0) return null;
      tx.insert(bookings).values(row).run();
      return row;
    });
    if (!created) {
      return {
        ok: false,
        error: "That time was just booked by someone else. Please pick another slot.",
      };
    }
    return { ok: true, booking: created as Booking };
  } catch {
    // Unique-index race fallback (uq_active_slot)
    return {
      ok: false,
      error: "That time was just booked by someone else. Please pick another slot.",
    };
  }
}

export function formatPrice(priceCents: number): string {
  return priceCents === 0
    ? "Free"
    : `$${(priceCents / 100).toFixed(priceCents % 100 === 0 ? 0 : 2)}`;
}

export function formatInTz(iso: string, timezone: string, format = "EEE, MMM d yyyy 'at' h:mm a"): string {
  return DateTime.fromISO(iso).setZone(timezone).toFormat(format);
}
