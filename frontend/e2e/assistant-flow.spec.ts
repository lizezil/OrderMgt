import { expect, test } from '@playwright/test'
import { ask, freshCustomer, openAs, openTab } from './support/viewer'

/**
 * The whole thesis of the product, driven through the browser: the assistant proposes, the person
 * confirms, and nothing mutating happens before that confirmation. The assistant here is the
 * `offline` profile's keyword adapter, so the flows are deterministic.
 */
test.describe('助手：提议、确认、落单', () => {
  test('只读的问题当场就答，不产生任何待授权卡片', async ({ page }) => {
    await openAs(page, freshCustomer())

    await ask(page, '我有哪些订单？')

    await expect(page.getByText('我先查一下你自己的订单。')).toBeVisible()
    await expect(page.locator('figure.slip')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '授权执行' })).toHaveCount(0)
    // One assistant turn, not two: the streamed placeholder is replaced by the persisted reply.
    await expect(page.locator('article.assistant')).toHaveCount(1)
  })

  test('变更只以卡片提议；确认之前订单页什么都不会有', async ({ page }) => {
    await openAs(page, freshCustomer())

    await ask(page, '帮我下单 2 个 GADGET-02')

    const slip = page.locator('figure.slip')
    await expect(slip).toBeVisible()
    await expect(slip.locator('.slip-kind')).toHaveText('创建订单')
    await expect(slip.locator('.slip-state')).toHaveText('待授权')
    await expect(slip).toContainText('GADGET-02')
    await expect(slip).toContainText('2')
    await expect(slip.locator('.clock')).toContainText('过期')

    await openTab(page, '订单')
    await expect(page.getByText('这个客户还没有订单')).toBeVisible()
  })

  test('授权之后订单才落地，金额由服务端按目录算出', async ({ page }) => {
    await openAs(page, freshCustomer())
    await ask(page, '帮我下单 2 个 GADGET-02')

    const slip = page.locator('figure.slip')
    await expect(slip.locator('.slip-state')).toHaveText('待授权')
    await slip.getByRole('button', { name: '授权执行' }).click()

    // Authorised cards stop being actionable: the UI must not offer a second confirmation.
    await expect(slip.locator('.slip-state')).toHaveText('已执行')
    await expect(slip.getByRole('button')).toHaveCount(0)
    await expect(slip).toContainText('未再执行')

    await openTab(page, '订单')
    const row = page.locator('table.ledger tbody tr')
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('99.00')
    await expect(row).toContainText('CREATED')
  })

  test('拒绝的卡片不产生订单，也不会再被点第二次', async ({ page }) => {
    await openAs(page, freshCustomer())
    await ask(page, '帮我下单 1 个 DOODAD-04')

    const slip = page.locator('figure.slip')
    await slip.getByRole('button', { name: '拒绝' }).click()

    await expect(slip.locator('.slip-state')).toHaveText('已拒绝')
    await expect(slip.getByRole('button')).toHaveCount(0)

    await openTab(page, '订单')
    await expect(page.getByText('这个客户还没有订单')).toBeVisible()
  })

  test('刷新之后历史里的卡片是只读的，仍然不给第二次确认', async ({ page }) => {
    await openAs(page, freshCustomer())
    await ask(page, '帮我下单 3 个 GIZMO-03')
    await page.locator('figure.slip').getByRole('button', { name: '授权执行' }).click()
    await expect(page.locator('figure.slip .slip-state')).toHaveText('已执行')

    await page.reload()

    const slip = page.locator('figure.slip')
    await expect(slip.locator('.slip-state')).toHaveText('已执行')
    await expect(slip).toContainText('未再执行')
    await expect(slip.getByRole('button')).toHaveCount(0)
  })

  test('回话是流式落定的：只出现一次，并标出这一轮的模型耗时', async ({ page }) => {
    await openAs(page, freshCustomer())

    await ask(page, '我有哪些订单？')

    await expect(page.getByText('我先查一下你自己的订单。')).toHaveCount(1)
    await expect(page.locator('.meter')).toContainText('模型耗时')
    await expect(page.locator('.meter')).toContainText('记忆窗口 2 条')
  })

  test('第一句话成为会话标题，并出现在侧栏里', async ({ page }) => {
    await openAs(page, freshCustomer())
    await expect(page.getByText('还没有对话')).toBeVisible()

    await ask(page, '我有哪些订单？')

    const entry = page.locator('.docket .entry')
    await expect(entry).toHaveCount(1)
    await expect(entry).toContainText('我有哪些订单？')
  })
})
