import { updateProfile, upgradePlan } from "@/app/actions/business";
import { BusinessForm } from "@/components/BusinessForm";
import { Badge, Button, Card } from "@/components/ui";
import { requireOwner } from "@/lib/tenant";

export const metadata = { title: "Settings — Bookly" };

export default async function SettingsPage() {
  const { business } = await requireOwner();
  const timezones = Intl.supportedValuesOf("timeZone");

  return (
    <div className="mx-auto max-w-2xl space-y-8">
      <div>
        <h1 className="mb-6 text-2xl font-bold">Settings</h1>
        <Card>
          <h2 className="mb-4 text-lg font-semibold">Business profile</h2>
          <BusinessForm
            action={updateProfile}
            timezones={timezones}
            submitLabel="Save changes"
            initial={{
              name: business.name,
              slug: business.slug,
              timezone: business.timezone,
              description: business.description,
            }}
          />
        </Card>
      </div>

      <Card>
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold">
              Plan:{" "}
              <Badge tone={business.plan === "pro" ? "indigo" : "slate"}>
                {business.plan === "pro" ? "Pro" : "Free"}
              </Badge>
            </h2>
            <p className="mt-1 text-sm text-slate-500">
              {business.plan === "pro"
                ? "You're on Pro — thanks for supporting Bookly!"
                : "Upgrade for unlimited services, priority support and more."}
            </p>
            <p className="mt-1 text-xs text-slate-400">
              Demo mode: upgrading is free and collects no payment.
            </p>
          </div>
          {business.plan !== "pro" && (
            <form action={upgradePlan}>
              <Button type="submit">Upgrade to Pro</Button>
            </form>
          )}
        </div>
      </Card>
    </div>
  );
}
