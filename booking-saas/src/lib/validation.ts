import { z } from "zod";
import { DateTime } from "luxon";

export const emailSchema = z
  .string()
  .trim()
  .toLowerCase()
  .email("Enter a valid email address");

export const signupSchema = z.object({
  email: emailSchema,
  password: z.string().min(8, "Password must be at least 8 characters"),
});

export const loginSchema = z.object({
  email: emailSchema,
  password: z.string().min(1, "Password is required"),
});

export const slugSchema = z
  .string()
  .trim()
  .toLowerCase()
  .min(3, "Slug must be at least 3 characters")
  .max(50, "Slug must be at most 50 characters")
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, "Use lowercase letters, numbers and dashes");

export const businessSchema = z.object({
  name: z.string().trim().min(2, "Name is too short").max(100),
  slug: slugSchema,
  timezone: z
    .string()
    .refine((tz) => DateTime.local().setZone(tz).isValid, "Unknown timezone"),
  description: z.string().trim().max(500).default(""),
});

export const serviceSchema = z.object({
  name: z.string().trim().min(2, "Name is too short").max(100),
  durationMin: z.coerce.number().int().min(5).max(24 * 60),
  priceCents: z.coerce.number().int().min(0).max(10_000_000),
  bufferMin: z.coerce.number().int().min(0).max(240),
});

export const availabilityWindowSchema = z
  .object({
    weekday: z.coerce.number().int().min(0).max(6),
    startMin: z.coerce.number().int().min(0).max(1439),
    endMin: z.coerce.number().int().min(1).max(1439),
  })
  .refine((w) => w.endMin > w.startMin, {
    message: "End time must be after start time",
  });

export const publicBookingSchema = z.object({
  serviceId: z.string().min(1),
  startUtc: z.string().datetime({ offset: false }),
  customerName: z.string().trim().min(2, "Name is too short").max(100),
  customerEmail: emailSchema,
  customerPhone: z.string().trim().max(30).default(""),
});

export const dateSchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Expected YYYY-MM-DD");
