import { expect, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

const APP_ROOT = path.resolve(__dirname, "..");
const SHOTS = path.join(__dirname, "screenshots");

// Fresh DB + outbox for every run (the webServer uses DATABASE_PATH=data/e2e.db).
test.beforeAll(() => {
  fs.mkdirSync(SHOTS, { recursive: true });
});

/** Next weekday (Mon–Fri) at least one day out, as YYYY-MM-DD. */
function nextWeekday(): string {
  const d = new Date();
  do {
    d.setDate(d.getDate() + 1);
  } while (d.getDay() === 0 || d.getDay() === 6);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

test("full booking journey", async ({ page, browser }) => {
  const ownerEmail = `owner-${Date.now()}@e2e.test`;
  const customerEmail = `casey-${Date.now()}@example.com`;

  // Landing page
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Reservations",
  );
  await page.screenshot({ path: path.join(SHOTS, "01-landing.png"), fullPage: true });

  // Signup
  await page.goto("/signup");
  await page.fill("#email", ownerEmail);
  await page.fill("#password", "supersecret1");
  await page.getByRole("button", { name: "Sign up" }).click();
  await page.waitForURL("**/onboarding");

  // Onboarding
  await page.fill("#name", "Acme Cuts");
  await page.fill("#slug", "acme-cuts");
  await page.selectOption("#timezone", "America/New_York");
  await page.fill("#description", "Classic cuts, modern fades.");
  await page.getByRole("button", { name: "Create business" }).click();
  await page.waitForURL("**/dashboard");

  // Add a service
  await page.goto("/dashboard/services");
  await page.fill("#svc-name", "Haircut");
  await page.fill("#svc-duration", "30");
  await page.fill("#svc-price", "25");
  await page.fill("#svc-buffer", "10");
  await page.getByRole("button", { name: "Add service" }).click();
  await expect(page.getByText("30 min · $25 · 10 min buffer")).toBeVisible();

  // Set Mon–Fri availability (defaults are 09:00–17:00, just enable the days)
  await page.goto("/dashboard/availability");
  for (let d = 0; d <= 4; d++) {
    await page.locator(`input[name="enabled_${d}"]`).check();
  }
  await page.getByRole("button", { name: "Save availability" }).click();
  await expect(
    page.getByRole("button", { name: "Save availability" }),
  ).toBeEnabled();
  await page.screenshot({ path: path.join(SHOTS, "02-availability.png"), fullPage: true });

  // Customer books in a fresh (logged-out) context
  const customer = await browser.newContext({
    baseURL: "http://localhost:3100",
  });
  const cpage = await customer.newPage();
  const date = nextWeekday();

  await cpage.goto("/b/acme-cuts");
  await expect(cpage.getByRole("heading", { name: "Acme Cuts" })).toBeVisible();
  await cpage.getByTestId("service-Haircut").click();
  await cpage.fill("#date", date);
  const slotList = cpage.getByTestId("slot-list");
  await expect(slotList).toBeVisible();
  await cpage.screenshot({ path: path.join(SHOTS, "03-slot-picker.png"), fullPage: true });

  const firstSlot = slotList.getByRole("button").first();
  const slotLabel = (await firstSlot.textContent())!.trim();
  await firstSlot.click();
  await cpage.fill("#customerName", "Casey Customer");
  await cpage.fill("#customerEmail", customerEmail);
  await cpage.fill("#customerPhone", "555-0100");
  await cpage.getByRole("button", { name: "Confirm booking" }).click();
  await cpage.waitForURL("**/confirmed/**");
  await expect(cpage.getByTestId("confirmation-title")).toContainText(
    "Booking received",
  );
  await cpage.screenshot({ path: path.join(SHOTS, "04-confirmation.png"), fullPage: true });

  // The just-booked slot must no longer be offered (double-booking guard)
  await cpage.goto("/b/acme-cuts");
  await cpage.getByTestId("service-Haircut").click();
  await cpage.fill("#date", date);
  await expect(cpage.getByTestId("slot-list")).toBeVisible();
  await expect(
    cpage.getByTestId("slot-list").getByRole("button", { name: slotLabel, exact: true }),
  ).toHaveCount(0);
  await customer.close();

  // Owner sees and confirms the booking
  await page.goto("/dashboard/bookings");
  const row = page.getByTestId(`booking-${customerEmail}`);
  await expect(row).toContainText("Casey Customer");
  await expect(row).toContainText("pending");
  await row.getByRole("button", { name: "Confirm" }).click();
  await expect(row).toContainText("confirmed");
  await page.screenshot({ path: path.join(SHOTS, "05-dashboard-bookings.png"), fullPage: true });

  // Mailer wrote to the outbox
  const outbox = fs.readFileSync(
    path.join(APP_ROOT, "data", "outbox.log"),
    "utf8",
  );
  expect(outbox).toContain(customerEmail);
  expect(outbox).toContain("Booking received — Haircut at Acme Cuts");
  expect(outbox).toContain("Confirmed — your booking at Acme Cuts");
});
