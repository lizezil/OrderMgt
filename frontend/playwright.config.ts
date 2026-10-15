import { defineConfig, devices } from '@playwright/test'

/**
 * Browser end-to-end suite: real Chromium, real MySQL, real confirm-then-execute loop.
 *
 * There is no Vite dev server and no proxy here. Playwright loads the *packaged* SPA from the app
 * itself (e2e/support/serve.mjs builds it into the backend's static resources and boots the jar's
 * classpath), so the page under test is the artifact the container ships, on the same origin as
 * the API -- which is exactly the arrangement the streaming reader and the session cookie assume.
 *
 * The assistant runs on the `offline` profile's keyword adapter, so every flow is deterministic
 * and no model call is billed. Only Chromium is exercised.
 */
const PORT = Number(process.env.E2E_PORT ?? 8082)
const BASE_URL = `http://127.0.0.1:${PORT}`

export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts$/,
  // One worker: the suite shares a single app instance and a single schema, and the tests read
  // best in file order.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'node e2e/support/serve.mjs',
    url: `${BASE_URL}/actuator/health`,
    // Never reuse: serve.mjs empties the schema and mints the customer pool the tests sign in as,
    // so a server left over from an earlier run would answer with the wrong identities (and a
    // schema full of last run's data). Every run boots its own.
    reuseExistingServer: false,
    timeout: 300_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})
