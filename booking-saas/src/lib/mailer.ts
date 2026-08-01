import "server-only";
import fs from "node:fs";
import path from "node:path";

export interface Mail {
  to: string;
  subject: string;
  body: string;
}

export interface Mailer {
  send(mail: Mail): Promise<void>;
}

/**
 * Development mailer: logs to the console and appends to data/outbox.log.
 * Swap for a real provider (SMTP, Resend, SES, ...) by implementing Mailer.
 */
class FileMailer implements Mailer {
  constructor(private readonly outboxPath: string) {}

  async send(mail: Mail): Promise<void> {
    const entry = [
      `--- ${new Date().toISOString()}`,
      `To: ${mail.to}`,
      `Subject: ${mail.subject}`,
      "",
      mail.body,
      "",
    ].join("\n");
    fs.mkdirSync(path.dirname(this.outboxPath), { recursive: true });
    fs.appendFileSync(this.outboxPath, entry);
    console.log(`[mailer] ${mail.to} — ${mail.subject}`);
  }
}

const dbDir = path.dirname(
  path.resolve(process.cwd(), process.env.DATABASE_PATH ?? "data/app.db"),
);

export const mailer: Mailer = new FileMailer(path.join(dbDir, "outbox.log"));
