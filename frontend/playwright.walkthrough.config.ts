import { defineConfig, devices } from '@playwright/test'

/**
 * A story-by-story walkthrough against the *running Docker container* (the compose stack: MySQL +
 * app; this suite never starts anything itself).
 *
 * Unlike playwright.config.ts there is no webServer here: this suite drives whatever is already
 * answering on APP_PORT (default 8081), because the point is to demo the shipped container itself.
 * Every step saves a full-page screenshot named after the user story it evidences.
 */
const BASE_URL = `http://127.0.0.1:${process.env.APP_PORT ?? 8081}`

export default defineConfig({
  testDir: './story-walk',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  // The walk respects the product's own send limiter, so it pauses ~65s twice mid-run (the three
  // proposals get a window, the referent questions plus the US-4.5 query get the next one); with
  // live model turns on top, the whole walk needs minutes, not the 90s it needed when it was 17
  // quick steps.
  timeout: 420_000,
  expect: { timeout: 20_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: BASE_URL,
    viewport: { width: 1440, height: 960 },
    deviceScaleFactor: 2,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
