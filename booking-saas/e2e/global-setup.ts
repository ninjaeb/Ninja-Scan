import fs from "node:fs";
import path from "node:path";

/** Clean artifacts of previous e2e runs. The current run uses its own unique
 *  DB file (see playwright.config.ts), so nothing here races the web server. */
export default function globalSetup() {
  const dataDir = path.resolve(__dirname, "..", "data");
  const current = path.basename(process.env.E2E_DB_CURRENT ?? "");
  fs.mkdirSync(dataDir, { recursive: true });
  for (const f of fs.readdirSync(dataDir)) {
    const isOldDb = f.startsWith("e2e-") && (!current || !f.startsWith(current));
    if (isOldDb || f === "outbox.log") {
      fs.rmSync(path.join(dataDir, f), { force: true });
    }
  }
}
