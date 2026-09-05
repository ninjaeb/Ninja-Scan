import { redirect } from "next/navigation";
import { createBusiness } from "@/app/actions/business";
import { BusinessForm } from "@/components/BusinessForm";
import { Card } from "@/components/ui";
import { getSessionUser } from "@/lib/auth";
import { getBusinessForOwner } from "@/lib/tenant";

export const metadata = { title: "Set up your business — Bookly" };

export default async function OnboardingPage() {
  const user = await getSessionUser();
  if (!user) redirect("/login");
  const business = await getBusinessForOwner(user.id);
  if (business) redirect("/dashboard");

  const timezones = Intl.supportedValuesOf("timeZone");

  return (
    <main className="mx-auto max-w-lg p-6 py-16">
      <h1 className="mb-1 text-2xl font-bold">Set up your business</h1>
      <p className="mb-8 text-slate-500">
        This creates your public booking page. You can change everything later.
      </p>
      <Card>
        <BusinessForm
          action={createBusiness}
          timezones={timezones}
          submitLabel="Create business"
        />
      </Card>
    </main>
  );
}
