"use server";

import { eq } from "drizzle-orm";
import { nanoid } from "nanoid";
import { revalidatePath } from "next/cache";
import { db, availabilityRules } from "@/db";
import { requireOwner } from "@/lib/tenant";
import { availabilityWindowSchema } from "@/lib/validation";
import type { FormState } from "./auth";

/**
 * Replaces the weekly schedule wholesale. The form posts, per weekday,
 * enabled_<d>, start_<d> and end_<d> (HH:MM). One window per weekday (MVP).
 */
export async function saveWeeklyAvailability(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const { business } = await requireOwner();

  const windows: { weekday: number; startMin: number; endMin: number }[] = [];
  for (let weekday = 0; weekday <= 6; weekday++) {
    if (formData.get(`enabled_${weekday}`) !== "on") continue;
    const toMin = (v: FormDataEntryValue | null) => {
      const m = /^(\d{2}):(\d{2})$/.exec(String(v ?? ""));
      return m ? Number(m[1]) * 60 + Number(m[2]) : NaN;
    };
    const parsed = availabilityWindowSchema.safeParse({
      weekday,
      startMin: toMin(formData.get(`start_${weekday}`)),
      endMin: toMin(formData.get(`end_${weekday}`)),
    });
    if (!parsed.success) {
      const day = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"][weekday];
      return { error: `${day}: ${parsed.error.issues[0].message}` };
    }
    windows.push(parsed.data);
  }

  db.transaction((tx) => {
    tx.delete(availabilityRules)
      .where(eq(availabilityRules.businessId, business.id))
      .run();
    for (const w of windows) {
      tx.insert(availabilityRules)
        .values({ id: nanoid(), businessId: business.id, ...w })
        .run();
    }
  });

  revalidatePath("/dashboard/availability");
  return {};
}
