import { describe, expect, it } from "vitest";
import { DateTime } from "luxon";
import {
  generateSlots,
  intervalsOverlap,
  weekdayOf,
  type GenerateSlotsInput,
} from "../src/lib/slots";

const TZ = "America/New_York";
const PAST = new Date("2026-01-01T00:00:00Z");

function base(overrides: Partial<GenerateSlotsInput> = {}): GenerateSlotsInput {
  return {
    date: "2026-08-10", // a Monday
    timezone: TZ,
    rules: [{ startMin: 9 * 60, endMin: 17 * 60 }],
    durationMin: 30,
    bufferMin: 0,
    existing: [],
    now: PAST,
    stepMin: 15,
    ...overrides,
  };
}

function utc(date: string, hourLocal: number, minute = 0): string {
  return DateTime.fromISO(date, { zone: TZ })
    .set({ hour: hourLocal, minute })
    .toUTC()
    .toISO()!;
}

describe("generateSlots", () => {
  it("returns empty for a day with no rules", () => {
    expect(generateSlots(base({ rules: [] }))).toEqual([]);
  });

  it("generates slots every step within the window", () => {
    const slots = generateSlots(base());
    // 9:00..16:30 starts inclusive, every 15 min = 31 slots
    expect(slots).toHaveLength(31);
    expect(slots[0].startLocalLabel).toBe("9:00 AM");
    expect(slots[0].startUtc).toBe(utc("2026-08-10", 9));
    expect(slots.at(-1)!.startLocalLabel).toBe("4:30 PM");
  });

  it("last slot fits exactly at the window end (half-open boundary)", () => {
    const slots = generateSlots(base({ durationMin: 60, stepMin: 60 }));
    // 9:00..16:00 hourly = 8 slots; a 16:00 start ends exactly at 17:00 and fits
    expect(slots.map((s) => s.startLocalLabel)).toContain("4:00 PM");
    expect(slots).toHaveLength(8);
  });

  it("excludes slots whose duration+buffer would spill past the window", () => {
    const slots = generateSlots(
      base({ durationMin: 60, bufferMin: 30, stepMin: 60 }),
    );
    // needs 90 min free: last valid start is 15:30 → hourly starts 9:00..15:00
    expect(slots.map((s) => s.startLocalLabel)).not.toContain("4:00 PM");
    expect(slots.at(-1)!.startLocalLabel).toBe("3:00 PM");
  });

  it("back-to-back with an existing booking does not conflict", () => {
    const existing = [
      { startUtc: utc("2026-08-10", 10), blockedUntilUtc: utc("2026-08-10", 10, 30) },
    ];
    const labels = generateSlots(base({ existing })).map((s) => s.startLocalLabel);
    expect(labels).not.toContain("10:00 AM");
    expect(labels).not.toContain("10:15 AM"); // would overlap 10:00–10:30
    expect(labels).toContain("10:30 AM"); // starts exactly when the block ends
    expect(labels).toContain("9:30 AM"); // ends exactly when the block starts
  });

  it("existing booking's buffer blocks following slots", () => {
    const existing = [
      // 10:00–10:30 booking with 15 min buffer → blocked until 10:45
      { startUtc: utc("2026-08-10", 10), blockedUntilUtc: utc("2026-08-10", 10, 45) },
    ];
    const labels = generateSlots(base({ existing })).map((s) => s.startLocalLabel);
    expect(labels).not.toContain("10:30 AM");
    expect(labels).toContain("10:45 AM");
  });

  it("candidate's own buffer prevents squeezing before an existing booking", () => {
    const existing = [
      { startUtc: utc("2026-08-10", 10), blockedUntilUtc: utc("2026-08-10", 10, 30) },
    ];
    const labels = generateSlots(base({ existing, bufferMin: 15 })).map(
      (s) => s.startLocalLabel,
    );
    // 9:30 start blocks 9:30–10:15 → overlaps the 10:00 booking
    expect(labels).not.toContain("9:30 AM");
    expect(labels).toContain("9:15 AM"); // blocks 9:15–10:00, touches only
  });

  it("trims slots at or before `now` mid-day", () => {
    const now = DateTime.fromISO("2026-08-10T12:00:00", { zone: TZ })
      .toJSDate();
    const labels = generateSlots(base({ now })).map((s) => s.startLocalLabel);
    expect(labels).not.toContain("11:45 AM");
    expect(labels).not.toContain("12:00 PM"); // exactly now is rejected
    expect(labels[0]).toBe("12:15 PM");
  });

  it("returns empty for a fully past date", () => {
    const now = new Date("2026-09-01T00:00:00Z");
    expect(generateSlots(base({ now }))).toEqual([]);
  });

  it("handles DST spring-forward correctly", () => {
    // 2026-03-08: America/New_York jumps 2:00→3:00 (EST→EDT).
    const slots = generateSlots(
      base({ date: "2026-03-08", rules: [{ startMin: 9 * 60, endMin: 11 * 60 }] }),
    );
    // 9:00 AM local on that day is 13:00 UTC (EDT, -04:00)
    expect(slots[0].startUtc).toBe("2026-03-08T13:00:00.000Z");
    expect(slots[0].startLocalLabel).toBe("9:00 AM");
  });

  it("supports multiple windows in one day, sorted output", () => {
    const slots = generateSlots(
      base({
        rules: [
          { startMin: 14 * 60, endMin: 15 * 60 },
          { startMin: 9 * 60, endMin: 10 * 60 },
        ],
        durationMin: 30,
        stepMin: 30,
      }),
    );
    expect(slots.map((s) => s.startLocalLabel)).toEqual([
      "9:00 AM",
      "9:30 AM",
      "2:00 PM",
      "2:30 PM",
    ]);
  });

  it("returns empty when duration exceeds the window", () => {
    expect(
      generateSlots(
        base({ rules: [{ startMin: 9 * 60, endMin: 9 * 60 + 45 }], durationMin: 60 }),
      ),
    ).toEqual([]);
  });

  it("returns empty for invalid date or non-positive duration", () => {
    expect(generateSlots(base({ date: "not-a-date" }))).toEqual([]);
    expect(generateSlots(base({ durationMin: 0 }))).toEqual([]);
  });
});

describe("weekdayOf", () => {
  it("maps Monday to 0 and Sunday to 6", () => {
    expect(weekdayOf("2026-08-10", TZ)).toBe(0);
    expect(weekdayOf("2026-08-16", TZ)).toBe(6);
  });
});

describe("intervalsOverlap", () => {
  const a = "2026-08-10T14:00:00.000Z";
  const b = "2026-08-10T14:30:00.000Z";
  const c = "2026-08-10T15:00:00.000Z";
  const d = "2026-08-10T15:30:00.000Z";

  it("detects overlap and respects half-open adjacency", () => {
    expect(intervalsOverlap(a, c, b, d)).toBe(true);
    expect(intervalsOverlap(a, b, b, c)).toBe(false); // adjacent
    expect(intervalsOverlap(a, b, c, d)).toBe(false); // disjoint
    expect(intervalsOverlap(a, d, b, c)).toBe(true); // containment
  });
});
