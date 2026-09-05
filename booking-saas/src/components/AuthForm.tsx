"use client";

import Link from "next/link";
import { useActionState } from "react";
import { Button, Card, ErrorText, Input, Label } from "@/components/ui";
import type { FormState } from "@/app/actions/auth";

export function AuthForm({
  mode,
  action,
}: {
  mode: "signup" | "login";
  action: (prev: FormState, formData: FormData) => Promise<FormState>;
}) {
  const [state, formAction, pending] = useActionState(action, {});
  const isSignup = mode === "signup";

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center p-6">
      <Link href="/" className="mb-6 text-center text-2xl font-bold text-indigo-600">
        Bookly
      </Link>
      <Card>
        <h1 className="mb-1 text-xl font-semibold">
          {isSignup ? "Create your account" : "Welcome back"}
        </h1>
        <p className="mb-6 text-sm text-slate-500">
          {isSignup
            ? "Start taking bookings in minutes."
            : "Log in to manage your bookings."}
        </p>
        <form action={formAction} className="space-y-4">
          <div>
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              name="email"
              type="email"
              required
              autoComplete="email"
              placeholder="you@business.com"
            />
          </div>
          <div>
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              required
              autoComplete={isSignup ? "new-password" : "current-password"}
              placeholder={isSignup ? "At least 8 characters" : "Your password"}
            />
          </div>
          <ErrorText>{state.error}</ErrorText>
          <Button type="submit" disabled={pending} className="w-full">
            {pending ? "Please wait…" : isSignup ? "Sign up" : "Log in"}
          </Button>
        </form>
      </Card>
      <p className="mt-4 text-center text-sm text-slate-500">
        {isSignup ? (
          <>
            Already have an account?{" "}
            <Link href="/login" className="font-medium text-indigo-600">
              Log in
            </Link>
          </>
        ) : (
          <>
            New to Bookly?{" "}
            <Link href="/signup" className="font-medium text-indigo-600">
              Create an account
            </Link>
          </>
        )}
      </p>
    </main>
  );
}
