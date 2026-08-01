import { sql } from "drizzle-orm";
import {
  index,
  integer,
  sqliteTable,
  text,
  uniqueIndex,
} from "drizzle-orm/sqlite-core";

export const users = sqliteTable("users", {
  id: text("id").primaryKey(),
  email: text("email").notNull().unique(),
  passwordHash: text("password_hash").notNull(),
  createdAt: text("created_at").notNull(),
});

export const businesses = sqliteTable(
  "businesses",
  {
    id: text("id").primaryKey(),
    ownerId: text("owner_id")
      .notNull()
      .references(() => users.id),
    name: text("name").notNull(),
    slug: text("slug").notNull().unique(),
    timezone: text("timezone").notNull(),
    description: text("description").notNull().default(""),
    plan: text("plan", { enum: ["free", "pro"] })
      .notNull()
      .default("free"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [index("idx_businesses_owner").on(t.ownerId)],
);

export const services = sqliteTable(
  "services",
  {
    id: text("id").primaryKey(),
    businessId: text("business_id")
      .notNull()
      .references(() => businesses.id),
    name: text("name").notNull(),
    durationMin: integer("duration_min").notNull(),
    priceCents: integer("price_cents").notNull().default(0),
    bufferMin: integer("buffer_min").notNull().default(0),
    active: integer("active", { mode: "boolean" }).notNull().default(true),
    createdAt: text("created_at").notNull(),
  },
  (t) => [index("idx_services_business").on(t.businessId)],
);

export const availabilityRules = sqliteTable(
  "availability_rules",
  {
    id: text("id").primaryKey(),
    businessId: text("business_id")
      .notNull()
      .references(() => businesses.id),
    // 0 = Monday .. 6 = Sunday
    weekday: integer("weekday").notNull(),
    // minutes from midnight, business-local wall time; endMin > startMin
    startMin: integer("start_min").notNull(),
    endMin: integer("end_min").notNull(),
  },
  (t) => [index("idx_avail_business_day").on(t.businessId, t.weekday)],
);

export const bookings = sqliteTable(
  "bookings",
  {
    id: text("id").primaryKey(),
    businessId: text("business_id")
      .notNull()
      .references(() => businesses.id),
    serviceId: text("service_id")
      .notNull()
      .references(() => services.id),
    customerName: text("customer_name").notNull(),
    customerEmail: text("customer_email").notNull(),
    customerPhone: text("customer_phone").notNull().default(""),
    startUtc: text("start_utc").notNull(),
    endUtc: text("end_utc").notNull(),
    // endUtc + service buffer; the interval [startUtc, blockedUntilUtc) is what conflicts
    blockedUntilUtc: text("blocked_until_utc").notNull(),
    status: text("status", { enum: ["pending", "confirmed", "cancelled"] })
      .notNull()
      .default("pending"),
    createdAt: text("created_at").notNull(),
  },
  (t) => [
    index("idx_bookings_business_start").on(t.businessId, t.startUtc),
    uniqueIndex("uq_active_slot")
      .on(t.businessId, t.startUtc)
      .where(sql`status != 'cancelled'`),
  ],
);

export type User = typeof users.$inferSelect;
export type Business = typeof businesses.$inferSelect;
export type Service = typeof services.$inferSelect;
export type AvailabilityRule = typeof availabilityRules.$inferSelect;
export type Booking = typeof bookings.$inferSelect;
