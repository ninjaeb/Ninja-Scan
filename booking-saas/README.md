# Bookly — Reservation & Booking SaaS

A multi-tenant reservation and booking web app for businesses. Owners sign up,
configure services and weekly opening hours, and customers book time slots on a
public page — no customer account required.

## Features

- **Business accounts** — email/password signup, HMAC-signed cookie sessions
- **Onboarding** — business profile (name, URL slug, timezone, description)
- **Services** — duration, price, and post-appointment buffer per service
- **Weekly availability** — per-weekday opening hours in the business timezone
- **Public booking page** at `/b/<slug>` — service → date → slot → book
- **Slot engine** — timezone- and DST-aware, respects duration + buffers,
  half-open intervals (back-to-back bookings allowed), no booking in the past
- **Double-booking prevention** — a partial unique index plus a synchronous
  transactional overlap check (better-sqlite3 is single-writer)
- **Dashboard** — upcoming/past bookings, month calendar, confirm/cancel,
  service & availability management, settings
- **Plans** — Free/Pro tiers with a stubbed upgrade flow (no real payments)
- **Notifications** — swappable `Mailer` interface; the dev implementation
  logs to the console and appends to `data/outbox.log`

## Stack

Next.js 15 (App Router, server actions) · React 19 · TypeScript ·
Drizzle ORM + better-sqlite3 (SQLite, WAL) · Tailwind CSS v4 · Luxon ·
Vitest · Playwright

## Getting started

```bash
cd booking-saas
npm install
cp .env.example .env   # set SESSION_SECRET
npm run dev
```

The database self-migrates on first open (`drizzle/` holds the SQL
migrations). Optionally seed a demo business:

```bash
npm run db:seed
# owner login: demo@bookly.test / password123
# public page: http://localhost:3000/b/acme-cuts
```

## Tests

```bash
npm test        # Vitest unit tests for the slot engine
npm run test:e2e  # Playwright: full signup → configure → book → confirm journey
```

## Architecture notes

- `src/lib/slots.ts` — pure slot-generation logic (no DB imports); all
  wall-clock math runs in the business's IANA timezone via Luxon
- `src/lib/bookings.ts` — transactional check-then-insert booking creation
- `src/lib/tenant.ts` — `requireOwner()` guard; every dashboard query filters
  by the owner's `businessId` (per-tenant isolation)
- Times are stored as UTC ISO strings; `availability_rules` store
  business-local minutes-from-midnight
- To move to Postgres later, swap the Drizzle driver and regenerate
  migrations; to send real email, implement `Mailer` in `src/lib/mailer.ts`
