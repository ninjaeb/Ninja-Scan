"use server";

import { eq } from "drizzle-orm";
import { nanoid } from "nanoid";
import { redirect } from "next/navigation";
import { db, users } from "@/db";
import {
  clearSessionCookie,
  hashPassword,
  setSessionCookie,
  verifyPassword,
} from "@/lib/auth";
import { getBusinessForOwner } from "@/lib/tenant";
import { loginSchema, signupSchema } from "@/lib/validation";

export interface FormState {
  error?: string;
}

export async function signup(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const parsed = signupSchema.safeParse({
    email: formData.get("email"),
    password: formData.get("password"),
  });
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }
  const { email, password } = parsed.data;

  const existing = await db.select().from(users).where(eq(users.email, email));
  if (existing.length > 0) {
    return { error: "An account with this email already exists. Try logging in." };
  }

  const id = nanoid();
  await db.insert(users).values({
    id,
    email,
    passwordHash: hashPassword(password),
    createdAt: new Date().toISOString(),
  });
  await setSessionCookie(id);
  redirect("/onboarding");
}

export async function login(
  _prev: FormState,
  formData: FormData,
): Promise<FormState> {
  const parsed = loginSchema.safeParse({
    email: formData.get("email"),
    password: formData.get("password"),
  });
  if (!parsed.success) {
    return { error: parsed.error.issues[0].message };
  }
  const { email, password } = parsed.data;

  const rows = await db.select().from(users).where(eq(users.email, email));
  const user = rows[0];
  if (!user || !verifyPassword(password, user.passwordHash)) {
    return { error: "Invalid email or password." };
  }

  await setSessionCookie(user.id);
  const business = await getBusinessForOwner(user.id);
  redirect(business ? "/dashboard" : "/onboarding");
}

export async function logout(): Promise<void> {
  await clearSessionCookie();
  redirect("/login");
}
