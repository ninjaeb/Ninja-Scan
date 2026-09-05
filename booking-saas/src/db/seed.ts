/**
 * Seed a demo business for local development:
 *   owner:  demo@bookly.test / password123
 *   public: /b/acme-cuts
 * Run with: npm run db:seed
 */
import bcrypt from "bcryptjs";
import { nanoid } from "nanoid";
import { DateTime } from "luxon";
import { db, users, businesses, services, availabilityRules } from "./index";

async function main() {
  const now = new Date().toISOString();
  const userId = nanoid();
  const businessId = nanoid();

  await db
    .insert(users)
    .values({
      id: userId,
      email: "demo@bookly.test",
      passwordHash: bcrypt.hashSync("password123", 10),
      createdAt: now,
    })
    .onConflictDoNothing();

  await db
    .insert(businesses)
    .values({
      id: businessId,
      ownerId: userId,
      name: "Acme Cuts",
      slug: "acme-cuts",
      timezone: "America/New_York",
      description: "Classic cuts, modern fades. Walk-ins welcome — but booking is faster.",
      plan: "free",
      createdAt: now,
    })
    .onConflictDoNothing();

  await db.insert(services).values([
    {
      id: nanoid(),
      businessId,
      name: "Haircut",
      durationMin: 30,
      priceCents: 2500,
      bufferMin: 10,
      active: true,
      createdAt: now,
    },
    {
      id: nanoid(),
      businessId,
      name: "Beard Trim",
      durationMin: 15,
      priceCents: 1200,
      bufferMin: 5,
      active: true,
      createdAt: now,
    },
    {
      id: nanoid(),
      businessId,
      name: "Cut + Beard Combo",
      durationMin: 45,
      priceCents: 3400,
      bufferMin: 10,
      active: true,
      createdAt: now,
    },
  ]);

  // Mon–Fri 9:00–17:00
  for (let weekday = 0; weekday <= 4; weekday++) {
    await db.insert(availabilityRules).values({
      id: nanoid(),
      businessId,
      weekday,
      startMin: 9 * 60,
      endMin: 17 * 60,
    });
  }

  console.log(
    `Seeded demo business "Acme Cuts" (${DateTime.now().toISO()})\n` +
      "  owner login: demo@bookly.test / password123\n" +
      "  public page: /b/acme-cuts",
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
