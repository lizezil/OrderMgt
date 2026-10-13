import { expect, test, type APIRequestContext } from '@playwright/test'
import { freshCustomer, openAs, openTab, switchViewer } from './support/viewer'

/**
 * "查询一律按请求头里的客户收窄." These tests place orders through the API -- who can see what is
 * the subject here, not how an order comes to exist -- and then look at the browser.
 */
async function placeOrder(
  request: APIRequestContext,
  customerId: string,
  sku: string,
  quantity: number,
): Promise<string> {
  const response = await request.post('/api/orders', {
    headers: { 'X-Customer-Id': customerId },
    data: { lines: [{ sku, quantity }] },
  })
  expect(response.status()).toBe(201)
  return (await response.json()).data.orderNo as string
}

test.describe('按客户隔离', () => {
  test('订单页只有本人的账目：换个客户号就空，换回来又出现', async ({ page, request }) => {
    const owner = freshCustomer()
    const stranger = freshCustomer()
    const orderNo = await placeOrder(request, owner, 'GADGET-02', 2)

    await openAs(page, owner)
    await openTab(page, '订单')
    const ledger = page.locator('table.ledger')
    await expect(ledger).toBeVisible()
    await expect(ledger).toContainText(orderNo)
    // 2 x 49.50, priced by the server from the one catalogue.
    await expect(ledger).toContainText('99.00')

    await switchViewer(page, stranger)
    await expect(page.locator('table.ledger')).toHaveCount(0)
    await expect(page.getByText('这个客户还没有订单')).toBeVisible()

    await switchViewer(page, owner)
    await expect(page.locator('table.ledger')).toContainText(orderNo)
  })

  test('助手替另一个客户查订单时，查不到本人的订单号', async ({ request }) => {
    const owner = freshCustomer()
    const stranger = freshCustomer()
    const orderNo = await placeOrder(request, owner, 'GIZMO-03', 1)

    const chat = await request.post('/api/assistant/chat', {
      headers: { 'X-Customer-Id': stranger },
      data: { message: 'what orders do I have?' },
    })
    expect(chat.ok()).toBe(true)
    const executed = (await chat.json()).data.executed as { name: string; resultJson: string }[]

    expect(executed.map((tool) => tool.name)).toContain('queryMyOrders')
    expect(executed[0].resultJson).toBe('[]')
    expect(JSON.stringify(executed)).not.toContain(orderNo)

    // The owner's own read does see it, so the emptiness above is scoping and not an empty table.
    const ownerChat = await request.post('/api/assistant/chat', {
      headers: { 'X-Customer-Id': owner },
      data: { message: 'what orders do I have?' },
    })
    const ownerTools = (await ownerChat.json()).data.executed as { resultJson: string }[]
    expect(ownerTools[0].resultJson).toContain(orderNo)
  })
})
