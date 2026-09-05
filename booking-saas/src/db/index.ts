import Database from "better-sqlite3";
import { drizzle } from "drizzle-orm/better-sqlite3";
import { migrate } from "drizzle-orm/better-sqlite3/migrator";
import fs from "node:fs";
import path from "node:path";
import * as schema from "./schema";

const DB_PATH = process.env.DATABASE_PATH ?? "data/app.db";

declare global {
  // eslint-disable-next-line no-var
  var __bookingDb: ReturnType<typeof createDb> | undefined;
}

function createDb() {
  const resolved = path.resolve(process.cwd(), DB_PATH);
  fs.mkdirSync(path.dirname(resolved), { recursive: true });
  const sqlite = new Database(resolved);
  sqlite.pragma("journal_mode = WAL");
  sqlite.pragma("foreign_keys = ON");
  const orm = drizzle(sqlite, { schema });
  // Self-migrate on open so a fresh checkout/database just works.
  migrate(orm, {
    migrationsFolder: path.resolve(process.cwd(), "drizzle"),
  });
  return { sqlite, orm };
}

// Reuse a single connection across Next.js dev hot reloads.
const instance = globalThis.__bookingDb ?? createDb();
globalThis.__bookingDb = instance;

export const sqlite = instance.sqlite;
export const db = instance.orm;
export * from "./schema";
