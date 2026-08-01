"use server";

import { and, eq } from "drizzle-orm";
import { nanoid } from "nanoid";
import { revalidatePath } from "next/cache";
import { db, services } from "@/db";
import { requireOwner } from "@/lib/tenant";
import { serviceSchema } from "@/lib/validation";
import type { FormState } from "./auth";

function parseService(formData: FormData) {
  return serviceSchema.safeParse({
    name: formData.get("name"),
    durationMin: formData.get("durationMin"),
    priceCents: Math.round(Number(formData.get("priceDollars") ?? 0) * 100),
    bufferMin: formData.get("bufferMin") ?? 0,
  });
}

export async function createService(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const { business } = await requireOwner();
  const parsed = parseService(formData);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  await db.insert(services).values({
    id: nanoid(),
    businessId: business.id,
    ...parsed.data,
    active: true,
    createdAt: new Date().toISOString(),
  });
  revalidatePath("/dashboard/services");
  return {};
}

export async function updateService(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const { business } = await requireOwner();
  const id = String(formData.get("id") ?? "");
  const parsed = parseService(formData);
  if (!parsed.success) return { error: parsed.error.issues[0].message };

  await db
    .update(services)
    .set(parsed.data)
    .where(and(eq(services.id, id), eq(services.businessId, business.id)));
  revalidatePath("/dashboard/services");
  return {};
}

export async function toggleService(formData: FormData): Promise<void> {
  const { business } = await requireOwner();
  const id = String(formData.get("id") ?? "");
  const row = await db
    .select()
    .from(services)
    .where(and(eq(services.id, id), eq(services.businessId, business.id)));
  if (row[0]) {
    await db
      .update(services)
      .set({ active: !row[0].active })
      .where(and(eq(services.id, id), eq(services.businessId, business.id)));
  }
  revalidatePath("/dashboard/services");
}
