import type { Page } from '@playwright/test'

/**
 * The e2e schema is shared across runs and never wiped, so every test claims a customer id nobody
 * else has used. Isolation then comes from the customer header -- the same mechanism production
 * uses -- and no assertion has to know what an earlier run left behind.
 *
 * Wall clock keeps ids in rough order; the random tail keeps two files apart within one
 * millisecond. The result stays inside a signed 64-bit integer.
 */
export function freshCustomer(): string {
  const tail = Math.floor(Math.random() * 1_000_000)
    .toString()
    .padStart(6, '0')
  return `${Date.now()}${tail}`
}

export type Tab = '助手' | '订单' | '商品'

/** Seeds the viewer before the app boots, so the very first fetch is already scoped to them. */
export async function openAs(page: Page, customerId: string): Promise<void> {
  await page.addInitScript((id) => {
    window.localStorage.setItem('ordermgt.customerId', id)
  }, customerId)
  await page.goto('/')
}

/** The masthead switch -- the same path the demo uses to show that data never crosses ids. */
export async function switchViewer(page: Page, customerId: string): Promise<void> {
  const field = page.getByLabel('当前客户')
  await field.fill(customerId)
  await field.press('Enter')
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
