import Link from "next/link";
import { getSessionUser } from "@/lib/auth";

const FEATURES = [
  {
    title: "Your own booking page",
    body: "A clean public page at your own URL where customers pick a service and a time — no account needed.",
  },
  {
    title: "Smart scheduling",
    body: "Slots follow your opening hours, service durations and buffers. Double bookings are impossible.",
  },
  {
    title: "Timezone-aware",
    body: "Set your business timezone once; every time shown to you and your customers is always right, DST included.",
  },
  {
    title: "Stay in control",
    body: "Confirm or cancel from a simple dashboard with list and calendar views. Customers are notified automatically.",
  },
];

const PLANS = [
  {
    name: "Free",
    price: "$0",
    tagline: "Everything you need to start",
    features: [
      "Public booking page",
      "Unlimited bookings",
      "Weekly availability & buffers",
      "Email notifications",
    ],
    cta: "Start free",
    highlighted: false,
  },
  {
    name: "Pro",
    price: "$19/mo",
    tagline: "For growing businesses",
    features: [
      "Everything in Free",
      "Unlimited services",
      "Priority support",
      "Coming soon: staff members, reminders & payments",
    ],
    cta: "Start with Pro",
    highlighted: true,
  },
];

export default async function LandingPage() {
  const user = await getSessionUser();

  return (
    <main>
      <header className="mx-auto flex max-w-5xl items-center justify-between p-6">
        <span className="text-xl font-bold text-indigo-600">Bookly</span>
        <nav className="flex items-center gap-4 text-sm">
          {user ? (
            <Link
              href="/dashboard"
              className="rounded-lg bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700"
            >
              Dashboard
            </Link>
          ) : (
            <>
              <Link href="/login" className="text-slate-600 hover:text-slate-900">
                Log in
              </Link>
              <Link
                href="/signup"
                className="rounded-lg bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700"
              >
                Get started
              </Link>
            </>
          )}
        </nav>
      </header>

      <section className="mx-auto max-w-3xl px-6 py-20 text-center">
        <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">
          Reservations &amp; bookings,
          <br />
          <span className="text-indigo-600">minus the phone tag</span>
        </h1>
        <p className="mx-auto mt-5 max-w-xl text-lg text-slate-600">
          Bookly gives your business a booking page customers love. Set your
          services and hours — we handle the calendar so nothing overlaps.
        </p>
        <div className="mt-8 flex justify-center gap-3">
          <Link
            href="/signup"
            className="rounded-lg bg-indigo-600 px-6 py-3 font-medium text-white hover:bg-indigo-700"
          >
            Create your booking page
          </Link>
          <Link
            href="/b/acme-cuts"
            className="rounded-lg border border-slate-300 px-6 py-3 font-medium text-slate-700 hover:bg-slate-50"
          >
            See a demo page
          </Link>
        </div>
      </section>

      <section className="border-y border-slate-100 bg-slate-50 py-16">
        <div className="mx-auto grid max-w-5xl gap-8 px-6 sm:grid-cols-2">
          {FEATURES.map((f) => (
            <div key={f.title}>
              <h3 className="font-semibold">{f.title}</h3>
              <p className="mt-1 text-sm text-slate-600">{f.body}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="mx-auto max-w-4xl px-6 py-16">
        <h2 className="mb-8 text-center text-3xl font-bold">
          Simple pricing
        </h2>
        <div className="grid gap-6 sm:grid-cols-2">
          {PLANS.map((plan) => (
            <div
              key={plan.name}
              className={`rounded-2xl border p-8 ${
                plan.highlighted
                  ? "border-indigo-600 shadow-lg shadow-indigo-100"
                  : "border-slate-200"
              }`}
            >
              <h3 className="text-lg font-semibold">{plan.name}</h3>
              <p className="mt-1 text-3xl font-bold">{plan.price}</p>
              <p className="mt-1 text-sm text-slate-500">{plan.tagline}</p>
              <ul className="mt-6 space-y-2 text-sm text-slate-600">
                {plan.features.map((f) => (
                  <li key={f} className="flex gap-2">
                    <span className="text-indigo-600">✓</span>
                    {f}
                  </li>
                ))}
              </ul>
              <Link
                href="/signup"
                className={`mt-8 block rounded-lg py-2.5 text-center font-medium ${
                  plan.highlighted
                    ? "bg-indigo-600 text-white hover:bg-indigo-700"
                    : "border border-slate-300 text-slate-700 hover:bg-slate-50"
                }`}
              >
                {plan.cta}
              </Link>
            </div>
          ))}
        </div>
      </section>

      <footer className="border-t border-slate-100 py-8 text-center text-sm text-slate-400">
        Bookly — demo SaaS. No real payments are processed.
      </footer>
    </main>
  );
}
