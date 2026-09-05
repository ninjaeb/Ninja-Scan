import { DateTime } from "luxon";

export interface AvailabilityWindow {
  /** minutes from business-local midnight */
  startMin: number;
  endMin: number;
}

export interface BlockedInterval {
  startUtc: string;
  blockedUntilUtc: string;
}

export interface Slot {
  startUtc: string;
  /** e.g. "9:15 AM" in the business timezone */
  startLocalLabel: string;
}

export interface GenerateSlotsInput {
  /** YYYY-MM-DD, interpreted in the business timezone */
  date: string;
  /** IANA timezone of the business */
  timezone: string;
  /** availability windows applying to that weekday */
  rules: AvailabilityWindow[];
  durationMin: number;
  bufferMin: number;
  /** active (non-cancelled) bookings whose blocked interval may touch the day */
  existing: BlockedInterval[];
  /** current instant; injected for testability */
  now: Date;
  stepMin?: number;
}

/**
 * Generate bookable slots for one day. All wall-clock math happens in the
 * business timezone via Luxon, so DST transitions resolve to correct UTC
 * instants. Intervals are half-open: [start, end) — back-to-back bookings
 * never conflict.
 */
export function generateSlots({
  date,
  timezone,
  rules,
  durationMin,
  bufferMin,
  existing,
  now,
  stepMin = 15,
}: GenerateSlotsInput): Slot[] {
  const dayStart = DateTime.fromISO(date, { zone: timezone }).startOf("day");
  if (!dayStart.isValid || durationMin <= 0) return [];

  const nowDt = DateTime.fromJSDate(now);
  const blockLen = durationMin + bufferMin;

  const blocked = existing.map((b) => ({
    start: DateTime.fromISO(b.startUtc),
    end: DateTime.fromISO(b.blockedUntilUtc),
  }));

  // Window bounds are wall-clock times: use set() rather than plus() so a
  // 9:00 opening is 9:00 local even on DST-transition days.
  const wall = (min: number) =>
    dayStart.set({ hour: Math.floor(min / 60), minute: min % 60 });

  const slots: Slot[] = [];
  for (const rule of rules) {
    const winStart = wall(rule.startMin);
    const winEnd = wall(rule.endMin);
    for (
      let t = winStart;
      t.plus({ minutes: blockLen }) <= winEnd;
      t = t.plus({ minutes: stepMin })
    ) {
      if (t <= nowDt) continue;
      const tEnd = t.plus({ minutes: blockLen });
      const conflict = blocked.some((b) => t < b.end && tEnd > b.start);
      if (conflict) continue;
      slots.push({
        startUtc: t.toUTC().toISO()!,
        startLocalLabel: t.setZone(timezone).toFormat("h:mm a"),
      });
    }
  }

  slots.sort((a, b) => a.startUtc.localeCompare(b.startUtc));
  return slots;
}

/** Luxon weekday is 1 (Mon) .. 7 (Sun); we store 0 (Mon) .. 6 (Sun). */
export function weekdayOf(date: string, timezone: string): number {
  const dt = DateTime.fromISO(date, { zone: timezone });
  return dt.isValid ? dt.weekday - 1 : -1;
}

/** True when interval [aStart, aEnd) overlaps [bStart, bEnd). */
export function intervalsOverlap(
  aStart: string,
  aEnd: string,
  bStart: string,
  bEnd: string,
): boolean {
  return aStart < bEnd && aEnd > bStart;
}
