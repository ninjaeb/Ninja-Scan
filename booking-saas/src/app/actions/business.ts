"use server";

import { eq } from "drizzle-orm";
import { nanoid } from "nanoid";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { db, businesses } from "@/db";
import { getSessionUser } from "@/lib/auth";
import { mailer } from "@/lib/mailer";
import { getBusinessForOwner, requireOwner } from "@/lib/tenant";
import { businessSchema } from "@/lib/validation";
import type { FormState } from "./auth";

export async function createBusiness(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const user = await getSessionUser();
  if (!user) redirect("/login");

  const existing = await getBusinessForOwner(user.id);
  if (existing) redirect("/dashboard");

  const parsed = businessSchema.safeParse({
    name: formData.get("name"),
    slug: formData.get("slug"),
    timezone: formData.get("timezone"),
    description: formData.get("description") ?? "",
  });
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }

  const slugTaken = await db
    .select({ id: businesses.id })
    .from(businesses)
    .where(eq(businesses.slug, parsed.data.slug));
  if (slugTaken.length > 0) {
    return { error: "That URL slug is already taken. Try another." };
  }

  await db.insert(businesses).values({
    id: nanoid(),
    ownerId: user.id,
    ...parsed.data,
    plan: "free",
    createdAt: new Date().toISOString(),
  });
  redirect("/dashboard");
}

export async function updateProfile(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const { business } = await requireOwner();

  const parsed = businessSchema.safeParse({
    name: formData.get("name"),
    slug: formData.get("slug"),
    timezone: formData.get("timezone"),
    description: formData.get("description") ?? "",
  });
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }

  if (parsed.data.slug !== business.slug) {
    const slugTaken = await db
      .select({ id: businesses.id })
      .from(businesses)
      .where(eq(businesses.slug, parsed.data.slug));
    if (slugTaken.length > 0) {
      return { error: "That URL slug is already taken. Try another." };
    }
  }

  await db
    .update(businesses)
    .set(parsed.data)
    .where(eq(businesses.id, business.id));
  revalidatePath("/dashboard/settings");
  return {};
}

/**
 * Stubbed billing: flips the plan and sends a fake receipt. Replace with a
 * real payment provider (Stripe Checkout, etc.) before charging anyone.
 */
export async function upgradePlan(): Promise<void> {
  const { user, business } = await requireOwner();
  if (business.plan === "pro") redirect("/dashboard/settings");

  await db
    .update(businesses)
    .set({ plan: "pro" })
    .where(eq(businesses.id, business.id));

  await mailer.send({
    to: user.email,
    subject: "Welcome to Bookly Pro",
    body: `Hi,\n\nYour business "${business.name}" is now on the Pro plan.\n(This is a demo receipt — no payment was collected.)\n\n— Bookly`,
  });
  redirect("/dashboard/settings");
}
