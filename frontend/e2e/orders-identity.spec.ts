import { expect, test, type APIRequestContext } from '@playwright/test'
import { freshCustomer, openAs, openTab, signInApi, switchViewer, xsrf } from './support/viewer'

/**
 * "查询一律按登录会话里的客户收窄." These tests place orders through the API -- who can see what is
 * the subject here, not how an order comes to exist -- and then look at the browser.
 *
 * Nobody can act as a customer without a session (ADRs 0006/0017), so each step signs the API
 * context in as the customer it wants to speak for; the browser signs in through the form.
 */
async function placeOrder(
  request: APIRequestContext,
  sku: string,
  quantity: number,
): Promise<string> {
  const response = await request.post('/api/orders', {
    headers: await xsrf(request),
    data: { lines: [{ sku, quantity }] },
  })
  expect(response.status()).toBe(201)
  return (await response.json()).data.orderNo as string
}

/** The tool calls a chat turn executed, as the API reports them. */
async function toolsOf(request: APIRequestContext, message: string): Promise<string[]> {
  const chat = await request.post('/api/assistant/chat', {
    headers: await xsrf(request),
    data: { message },
  })
  expect(chat.ok()).toBe(true)
  const executed = (await chat.json()).data.executed as { name: string; resultJson: string }[]
  return executed.map((tool) => tool.resultJson)
}

test.describe('按客户隔离', () => {
  test('订单页只有本人的账目：换个客户号就空，换回来又出现', async ({ page, request }) => {
    const owner = freshCustomer()
    const stranger = freshCustomer()
    await signInApi(request, owner)
    const orderNo = await placeOrder(request, 'GADGET-02', 2)

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
    await signInApi(request, owner)
    const orderNo = await placeOrder(request, 'GIZMO-03', 1)

    // The stranger's own read: the tool runs, sees an empty table, and never leaks the order number.
    await signInApi(request, stranger)
    const strangerReads = await toolsOf(request, 'what orders do I have?')
    expect(strangerReads).toHaveLength(1)
    const strangerPayload = JSON.parse(strangerReads[0]!) as { total: number; orders: unknown[] }
    expect(strangerPayload.total).toBe(0)
    expect(strangerPayload.orders).toEqual([])
    expect(strangerReads[0]).not.toContain(orderNo)

    // The owner's own read does see it, so the emptiness above is scoping and not an empty table.
    await signInApi(request, owner)
    const ownerReads = await toolsOf(request, 'what orders do I have?')
    expect(ownerReads[0]).toContain(orderNo)
  })
})
