import { expect, test, type Page } from '@playwright/test'

/** Is the open conversation preserved across a tab switch (the fix for the jump-to-newest bug)? */
test('切页后主区：稳定态是否恢复会话', async ({ page }) => {
  const errors: string[] = []
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()))
  page.on('pageerror', (e) => errors.push(String(e)))

  await page.goto('/')
  await page.getByTestId('customer').fill('1001')
  await page.getByTestId('pin').fill('1111')
  await page.getByTestId('submit').click()
  await expect(page.locator('.identity-name')).toContainText('1001')

  // 打开一个"不是最新"的会话（点侧栏第二行），再切页，看回来时停在哪儿。
  await page.getByRole('button', { name: '订单', exact: true }).click()
  await page.getByRole('button', { name: '助手', exact: true }).click()
  await expect(page.locator('ol.docket > li').first()).toBeVisible({ timeout: 5_000 })
  const second = page.locator('ol.docket > li').nth(1)
  const secondTitle = (await second.textContent())?.trim().slice(0, 24)
  await second.locator('button.entry').click()
  await expect(second.locator('button.entry.active')).toBeVisible({ timeout: 5_000 })
  const activeBefore = await page.locator('button.entry.active')
    .first()
    .textContent()
    .then((t) => t?.trim().slice(0, 24))
  await expect(page.locator('article.line').first()).toBeVisible({ timeout: 5_000 })
  const openedTitle = await page.locator('.line').first().textContent()

  await page.getByRole('button', { name: '订单', exact: true }).click()
  await page.getByRole('button', { name: '助手', exact: true }).click()
  // 给重挂的加载留时间，再看稳定态。
  await page.waitForTimeout(2_000)
  const blankNow = await page.locator('p.blank').isVisible()
  const activeAfter = await page.locator('button.entry.active')
    .first()
    .textContent()
    .then((t) => t?.trim().slice(0, 24))
  const lines = await page.locator('article.line').count()
  console.log(
    `[probe] 切页前高亮「${activeBefore}」 vs 切页后高亮「${activeAfter}」 ` +
      `(blank=${blankNow}, 消息条数=${lines})`,
  )
  // 回归断言：切页回来必须还停在原来那段会话上。
  expect(activeAfter).toBe(activeBefore)
  expect(blankNow).toBe(false)
  console.log(`[probe] 控制台错误 ${errors.length} 条`)
  errors.slice(0, 5).forEach((e) => console.log(`  ! ${e}`))
  await page.screenshot({ path: 'test-results/probe-tabswitch.png', fullPage: true })
})
