import { expect, test } from '@playwright/test'
import { ask, freshCustomer, openAs } from './support/viewer'

/**
 * 归档与恢复在浏览器里的样子（ADR-0013）：归档把一段会话收进底部折叠组，它仍可读、可一键恢复；归档只
 * 管可见性——有未授权卡片时服务端拒绝，前端如实报错；全程没有任何硬删除入口。
 */
test.describe('会话管理：归档与恢复', () => {
  test('归档收进折叠组、恢复放回主列表，且只有归档/恢复两个动作', async ({ page }) => {
    await openAs(page, freshCustomer())
    await ask(page, 'what orders do I have?')
    await expect(page.getByText('我查一下你自己的订单。')).toBeVisible()

    const activeRows = page.locator('.docket > .row')
    await expect(activeRows).toHaveCount(1)

    // 行溢出菜单里只有「归档」——没有删除。
    await page.locator('.entry-more').first().click()
    await expect(page.locator('.entry-menu .menu-item')).toHaveText('归档')
    await expect(page.locator('.entry-menu')).not.toContainText('删除')
    await page.locator('.entry-menu .menu-item').click()

    // 归档后主列表空了，它落进底部折叠组，默认不展开。
    await expect(activeRows).toHaveCount(0)
    await expect(page.locator('.archived-toggle')).toContainText('已归档 1')
    await expect(page.locator('.docket > .row')).toHaveCount(0)

    await page.locator('.archived-toggle').click()
    await expect(page.locator('.docket > .row')).toHaveCount(1)
    await expect(page.locator('.docket > .row')).toContainText('what orders do I have?')

    // 恢复把它放回主列表，归档组随之消失。
    await page.locator('.entry-more').first().click()
    await expect(page.locator('.entry-menu .menu-item')).toHaveText('恢复')
    await page.locator('.entry-menu .menu-item').click()

    await expect(page.locator('.archived-toggle')).toHaveCount(0)
    await expect(activeRows).toHaveCount(1)
  })

  test('有未授权卡片的会话归档被拒：如实报错，会话留在原处', async ({ page }) => {
    await openAs(page, freshCustomer())
    await ask(page, 'help me order 2 GADGET-02')
    await expect(page.locator('figure.slip')).toBeVisible()

    await page.locator('.entry-more').first().click()
    await page.locator('.entry-menu .menu-item').click()

    // 非静止的会话归档是 409：变成一条错误提示，行没有被收走。
    await expect(page.locator('.toast.error')).toBeVisible()
    await expect(page.locator('.archived-toggle')).toHaveCount(0)
    await expect(page.locator('.docket > .row')).toHaveCount(1)
  })
})
