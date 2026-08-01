import "server-only";
import { eq } from "drizzle-orm";
import { redirect } from "next/navigation";
import { db, businesses, type Business, type User } from "@/db";
import { getSessionUser } from "./auth";

/** The one business owned by this user (MVP: one business per owner). */
export async function getBusinessForOwner(
  userId: string,
): Promise<Business | null> {
  const rows = await db
    .select()
    .from(businesses)
    .where(eq(businesses.ownerId, userId));
  return rows[0] ?? null;
}

/**
 * Auth + tenant guard for dashboard pages and actions. Redirects to /login
 * when signed out and to /onboarding when no business exists yet.
 */
export async function requireOwner(): Promise<{
  user: User;
  business: Business;
}> {
  const user = await getSessionUser();
  if (!user) redirect("/login");
  const business = await getBusinessForOwner(user.id);
  if (!business) redirect("/onboarding");
  return { user, business };
}
