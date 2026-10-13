import { expect, test } from '@playwright/test'
import { freshCustomer, openAs, openTab } from './support/viewer'

interface CatalogItem {
  sku: string
  name: string
  unitPrice: number
}

/** What the migration seeds. The prices here are the only prices in the system. */
const SEEDED = [
  { sku: 'WIDGET-01', unitPrice: 19.9 },
  { sku: 'GADGET-02', unitPrice: 49.5 },
  { sku: 'GIZMO-03', unitPrice: 5 },
  { sku: 'DOODAD-04', unitPrice: 120 },
  { sku: 'THING-05', unitPrice: 9.99 },
]

test.describe('商品目录', () => {
  test('页面上的每一行都等于服务端说的那一行，名称与单价一字不差', async ({ page, request }) => {
    const customer = freshCustomer()
    await openAs(page, customer)

    const response = await request.get('/api/catalog/items', {
      headers: { 'X-Customer-Id': customer },
    })
    expect(response.ok()).toBe(true)
    const items = (await response.json()).data as CatalogItem[]
    expect(items.map((item) => item.sku).sort()).toEqual(SEEDED.map((item) => item.sku).sort())

    await openTab(page, '商品')
    await expect(page.locator('table.ledger tbody tr')).toHaveCount(items.length)
    for (const item of items) {
      const row = page.locator('table.ledger tbody tr', { hasText: item.sku })
      await expect(row).toContainText(item.name)
      await expect(row).toContainText(item.unitPrice.toFixed(2))
    }
  })

  test('目录只对外暴露 sku、名称与单价，没有可以让模型回填价格的字段', async ({ request }) => {
    const response = await request.get('/api/catalog/items', {
      headers: { 'X-Customer-Id': freshCustomer() },
    })
    expect(response.ok()).toBe(true)
    const items = (await response.json()).data as Record<string, unknown>[]

    expect(items.length).toBe(SEEDED.length)
    for (const item of items) {
      expect(Object.keys(item).sort()).toEqual(['name', 'sku', 'unitPrice'])
    }
    for (const seeded of SEEDED) {
      const shown = items.find((item) => item.sku === seeded.sku)
      expect(shown?.unitPrice).toBe(seeded.unitPrice)
    }
  })
})
