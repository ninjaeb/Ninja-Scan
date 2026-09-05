import Link from "next/link";
import { logout } from "@/app/actions/auth";
import { Badge } from "@/components/ui";
import { requireOwner } from "@/lib/tenant";

const NAV = [
  { href: "/dashboard", label: "Overview" },
  { href: "/dashboard/bookings", label: "Bookings" },
  { href: "/dashboard/calendar", label: "Calendar" },
  { href: "/dashboard/services", label: "Services" },
  { href: "/dashboard/availability", label: "Availability" },
  { href: "/dashboard/settings", label: "Settings" },
];

export default async function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { business } = await requireOwner();

  return (
    <div className="flex min-h-screen bg-slate-50">
      <aside className="flex w-56 flex-col border-r border-slate-200 bg-white p-4">
        <Link href="/" className="mb-1 px-2 text-xl font-bold text-indigo-600">
          Bookly
        </Link>
        <div className="mb-6 px-2 text-sm text-slate-500">
          {business.name}{" "}
          <Badge tone={business.plan === "pro" ? "indigo" : "slate"}>
            {business.plan === "pro" ? "Pro" : "Free"}
          </Badge>
        </div>
        <nav className="flex flex-1 flex-col gap-1">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="rounded-lg px-2 py-1.5 text-sm text-slate-700 hover:bg-slate-100"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="space-y-2 border-t border-slate-200 pt-3">
          <Link
            href={`/b/${business.slug}`}
            className="block rounded-lg px-2 py-1.5 text-sm text-indigo-600 hover:bg-indigo-50"
          >
            View booking page ↗
          </Link>
          <form action={logout}>
            <button
              type="submit"
              className="w-full rounded-lg px-2 py-1.5 text-left text-sm text-slate-500 hover:bg-slate-100"
            >
              Log out
            </button>
          </form>
        </div>
      </aside>
      <main className="flex-1 p-8">{children}</main>
    </div>
  );
}
