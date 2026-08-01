import { defineConfig } from "@playwright/test";

// Unique DB per run: the app self-migrates on open, and a fresh path avoids
// deleting a file the (possibly already-started) web server holds open.
const E2E_DB = `data/e2e-${Date.now()}.db`;
// Let global-setup skip the current run's files when cleaning old ones
// (config and globalSetup share this process).
process.env.E2E_DB_CURRENT = E2E_DB;

export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.ts",
  timeout: 60_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: "http://localhost:3100",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "npm run dev -- --port 3100",
    url: "http://localhost:3100",
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      DATABASE_PATH: E2E_DB,
      SESSION_SECRET: "e2e-test-secret-not-for-production",
      NEXT_TELEMETRY_DISABLED: "1",
    },
  },
});
