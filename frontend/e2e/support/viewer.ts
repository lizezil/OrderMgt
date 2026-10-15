import { expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * The customer is the session, not a header. Identity comes from the deployment's configured
 * customerId:PIN pairs (ADR-0017 -- there is no user table), so a test cannot mint an account the
 * way the old `X-Customer-Id` helper did; it signs in as one the booted app knows.
 *
 * That pool lives in `serve.mjs`, which also empties the schema before every run. Here we only
 * allocate from it: one id per `freshCustomer()`, and because isolation now comes from signing in
 * as *this* customer, no test has to know what an earlier one left behind. The three constants
 * below must stay in step with `POOL_BASE` / `POOL_SIZE` / `POOL_PIN` there.
 */
const POOL_BASE = 700000
const POOL_SIZE = 40
const POOL_PIN = '4242'

export interface Identity {
  id: string
  pin: string
}

let claimed = 0

/** The next unclaimed customer of this run. Exhausting the pool is a bug, not a flake. */
export function freshCustomer(): Identity {
  if (claimed >= POOL_SIZE) {
    throw new Error(
      `this run claimed more than ${POOL_SIZE} customers; raise POOL_SIZE in serve.mjs and the ` +
        'matching constant here.',
    )
  }
  claimed += 1
  return { id: String(POOL_BASE + claimed), pin: POOL_PIN }
}

export type Tab = '助手' | '订单' | '商品'

/** Signs in through the login form -- the same door a customer walks through (US-6.0). */
export async function signIn(page: Page, who: Identity): Promise<void> {
  await expect(page.locator('form.login')).toBeVisible()
  await page.getByTestId('customer').fill(who.id)
  await page.getByTestId('pin').fill(who.pin)
  await page.getByTestId('submit').click()
  await expect(page.locator('.identity-name')).toContainText(who.id)
}

/** Ends the session and lands back on the login page. */
export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('logout').click()
  await expect(page.locator('form.login')).toBeVisible()
}

/** Opens the app and signs in as `who`, so the very first fetch is already scoped to them. */
export async function openAs(page: Page, who: Identity): Promise<void> {
  await page.goto('/')
  await signIn(page, who)
}

/** Signs out and back in as somebody else -- the visible half of "queries are scoped by session". */
export async function switchViewer(page: Page, who: Identity): Promise<void> {
  await signOut(page)
  await signIn(page, who)
}

/**
 * The XSRF token the server planted for this session, echoed back the way the SPA does. The API
 * context shares the session cookie with whatever it signed in as; a mutation still needs the token
 * in a header (CSRF is on in the running app).
 */
export async function xsrf(request: APIRequestContext): Promise<Record<string, string>> {
  const cookies = (await request.storageState()).cookies
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value
  return token ? { 'X-XSRF-TOKEN': token } : {}
}

/**
 * Signs an {@link APIRequestContext} in, for the assertions that belong at the API layer. The CSRF
 * cookie has to exist before the login POST, exactly as the SPA orders it.
 */
export async function signInApi(request: APIRequestContext, who: Identity): Promise<void> {
  await request.get('/api/auth/csrf')
  const response = await request.post('/api/auth/login', {
    headers: await xsrf(request),
    data: { customerId: who.id, pin: who.pin },
  })
  expect(response.ok(), `signing in as ${who.id} over the API`).toBeTruthy()
}

export async function openTab(page: Page, tab: Tab): Promise<void> {
  await page.getByRole('button', { name: tab, exact: true }).click()
}

/** Types into the assistant composer and sends, the way a customer does. */
export async function ask(page: Page, message: string): Promise<void> {
  const composer = page.getByPlaceholder('说点什么…（Enter 发送）')
  await composer.fill(message)
  await composer.press('Enter')
}
