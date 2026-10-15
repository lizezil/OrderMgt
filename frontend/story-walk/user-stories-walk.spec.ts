import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'

/**
 * A guided walk through docs/用户故事.md, one screenshot per story, against the running Docker
 * container (image built from this repo). Verified under both profiles: with `mysql,offline` the
 * assistant is the keyword stub, and with plain `mysql` (the real model gateway) the scripted
 * sentences below still hold up -- they are plain Chinese requests, not stub-only incantations.
 * Stories whose acceptance lives in the API layer rather than
 * in pixels (US-5.2 replay, US-6.2 foreign token) are asserted via requests and logged.
 */
const OUT_DIR = path.resolve(
  fileURLToPath(new URL('.', import.meta.url)),
  '..',
  '..',
  'story-screenshots',
)
fs.mkdirSync(OUT_DIR, { recursive: true })

const CREATOR = { id: '1001', pin: '1111' }
const OTHER = { id: '2002', pin: '2222' }

let shot = 0
async function snap(page: Page, label: string): Promise<void> {
  const name = `${String(++shot).padStart(2, '0')}-${label.replace(/\.png$/, '')}.png`
  await page.screenshot({ path: path.join(OUT_DIR, name), fullPage: true })
  console.log(`[shot] ${name}`)
}

async function tab(page: Page, name: '助手' | '订单' | '商品'): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click()
}

/** Types into the composer and sends, then waits for the assistant's turn to settle. */
async function ask(page: Page, message: string): Promise<void> {
  const composer = page.getByPlaceholder('说点什么…（Enter 发送）')
  await composer.fill(message)
  await composer.press('Enter')
  await expect(page.locator('.thinking')).toHaveCount(0, { timeout: 30_000 })
}

const assistantTurn = (page: Page) => page.locator('article.line.assistant').last()
const slip = (page: Page) => page.locator('figure.slip').last()

async function orderNumbers(page: Page): Promise<Set<string>> {
  const res = await page.request.get('/api/orders?page=1&size=100')
  const body = await res.json()
  return new Set<string>((body.data.items ?? []).map((o: { orderNo: string }) => o.orderNo))
}

async function login(page: Page, id: string, pin: string): Promise<void> {
  await page.getByTestId('customer').fill(id)
  await page.getByTestId('pin').fill(pin)
  await page.getByTestId('submit').click()
  await expect(page.locator('.identity-name')).toContainText(id)
}

test.describe('用户故事走查（Docker 容器，offline 桩）', () => {
  test('E1/E4/E6 核心闭环：登录 → 查单 → 提议 → 确认 → 拒绝 → 取消 → 换客户', async ({
    page,
    browser,
  }) => {
    // ---------- US-6.0 登录 ----------
    await page.goto('/')
    await expect(page.locator('form.login')).toBeVisible()
    await snap(page, 'US-6.0-登录页.png')

    await page.getByTestId('customer').fill(CREATOR.id)
    await page.getByTestId('pin').fill('0000')
    await page.getByTestId('submit').click()
    await expect(page.locator('.login-error')).toHaveText('客户编号或 PIN 不正确')
    await snap(page, 'US-6.0-错误PIN共用一条消息.png')
    await login(page, CREATOR.id, CREATOR.pin)
    await snap(page, 'US-6.0-登录后进入助手.png')

    // ---------- US-4.4 商品目录（价格只在这里定义） ----------
    await tab(page, '商品')
    await expect(page.locator('tbody tr').first()).toBeVisible()
    await snap(page, 'US-4.4-商品与单价.png')

    // ---------- US-1.1 / US-1.2 新对话 + 自然语言只读查询 ----------
    await tab(page, '助手')
    await page.getByRole('button', { name: '＋ 新对话' }).click()
    await ask(page, '我有哪些订单')
    await expect(assistantTurn(page)).toContainText('你名下')
    // The thread auto-scrolls to its bottom; the evidence for US-1.2 is its opening line.
    await page.evaluate(() => {
      const el = document.querySelector('.sheet-scroll')
      if (el) el.scrollTop = 0
    })
    await snap(page, 'US-1.1-1.2-新对话与查询订单成中文人话.png')

    // ---------- US-1.4 提议创建订单：只出卡片，库里没有新单 ----------
    const before = await orderNumbers(page)
    await ask(page, '帮我下单 2 个 GADGET-02')
    await expect(slip(page)).toBeVisible()
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await snap(page, 'US-1.4-待授权卡片_字段来自工具.png')
    const midCheck = await orderNumbers(page)
    expect(midCheck.size, 'US-1.4: 确认之前不得落单').toBe(before.size)

    await tab(page, '订单')
    await expect(page.locator('table.ledger tbody tr, p.empty').first()).toBeVisible()
    await snap(page, 'US-1.4-确认前订单页无新单.png')

    // ---------- US-1.5 确认执行：事务内落单，服务端定价 ----------
    await tab(page, '助手')
    await slip(page).getByRole('button', { name: '授权执行' }).click()
    await expect(slip(page).locator('.slip-state')).toHaveText('已执行')
    await snap(page, 'US-1.5-确认后卡片盖章已执行.png')
    const after = await orderNumbers(page)
    expect(after.size).toBe(before.size + 1)
    const newOrder = [...after].find((n) => !before.has(n))!
    console.log(`[story] createOrder 落单订单号: ${newOrder}`)

    // ---------- US-1.6 拒绝提议：什么都不发生 ----------
    await ask(page, '帮我下单 1 个 WIDGET-01')
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await slip(page).getByRole('button', { name: '拒绝' }).click()
    await expect(slip(page).locator('.slip-state')).toHaveText('已拒绝')
    await snap(page, 'US-1.6-拒绝后卡片显示已拒绝.png')
    expect((await orderNumbers(page)).size, 'US-1.6: 拒绝不得落单').toBe(after.size)

    // ---------- US-1.7 取消订单：同构的 提议→确认 ----------
    await ask(page, `取消 ${newOrder}`)
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await slip(page).getByRole('button', { name: '授权执行' }).click()
    await expect(slip(page).locator('.slip-state')).toHaveText('已执行')
    await snap(page, 'US-1.7-取消订单的确认卡片.png')

    // ---------- US-1.9 「最近一次下单」答的就是最近那一单（新会话，问不出别的会话的事实） ----------
    await page.getByRole('button', { name: '＋ 新对话' }).click()
    await ask(page, '查询最近一次下单')
    const latest = (await assistantTurn(page).innerText()).replace(/\s+/g, ' ')
    // 断言的是**保证**那一道闸：渲染器必须点名最近一张，且点名的就是刚才落的那一单。
    // 「只列一单」是 `limit` 那道**能力**闸，模型可传可不传，所以不在这里断言（离线桩下由单测钉住）。
    expect(latest, 'US-1.9: 引导句要点名最近一张').toContain(`最近一张是 ${newOrder}`)
    const listed = latest.match(/ORD[0-9A-Z]+/g) ?? []
    expect(listed[0], 'US-1.9: 清单第一行就是最近那一单').toBe(newOrder)
    await snap(page, 'US-1.9-最近一次下单只点名那一单.png')

    // ---------- US-4.1 / US-4.2 订单列表与详情 ----------
    await tab(page, '订单')
    await expect(page.locator(`td.order-no:text("${newOrder}")`)).toBeVisible()
    await snap(page, 'US-4.1-订单列表_按客户收窄.png')
    await page.locator(`tr:has(td.order-no:text("${newOrder}"))`).click()
    const detail = page.locator('section.docket-open')
    await expect(detail).toBeVisible()
    await detail.scrollIntoViewIfNeeded()
    await snap(page, 'US-4.2-订单详情_明细与合计_已取消.png')

    // ---------- US-2.3 / US-2.4 历史会话与自动标题 ----------
    await tab(page, '助手')
    await expect(page.locator('ol.docket li').first()).toBeVisible()
    // 修复回归：切页回来应仍停在刚才那段对话（有内容，而不是空白引导）。
    await expect(page.locator('article.line').first()).toBeVisible()
    await snap(page, 'US-2.3-2.4-侧栏历史与自动标题.png')

    // ---------- US-5.2 / US-6.2（API 层断言：重复确认走重放；他人令牌 403） ----------
    async function xsrf(p: Page): Promise<Record<string, string>> {
      const cookie = p
        .context()
        .cookies()
        .then((all) => all.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '')
      return { 'X-XSRF-TOKEN': await cookie }
    }
    const convosRes = await page.request.get('/api/assistant/conversations')
    const convosBody = await convosRes.json()
    const convos = convosBody.data.items ?? convosBody.data
    const myConvo = convos.find((c: { title?: string }) => (c.title ?? '').includes('我有哪些订单'))
    expect(myConvo, 'US-2.4: 标题由第一条客户消息派生').toBeTruthy()
    const msgsRes = await page.request.get(`/api/assistant/conversations/${myConvo.id}/messages`)
    const cards = (
      ((await msgsRes.json()).data ?? []) as {
        action: { token: string; status: string; toolName: string } | null
      }[]
    )
      .map((m) => m.action)
      .filter((a): a is NonNullable<typeof a> => a !== null)
    const confirmedCard = cards.find((a) => a.status === 'CONFIRMED')
    expect(confirmedCard, '历史里卡片解析为当前状态').toBeTruthy()

    const replay = await page.request.post(
      `/api/assistant/actions/${confirmedCard!.token}/confirm`,
      { headers: await xsrf(page) },
    )
    const replayBody = await replay.json()
    console.log(
      `[story] US-5.2 重复确认 → HTTP ${replay.status()} replayed=${replayBody.data?.replayed} ` +
        `结果仍指向: ${replayBody.data?.resultJson ?? '-'}`,
    )
    expect(replay.status()).toBe(200)
    expect(replayBody.data.replayed).toBe(true)

    // ---------- US-6.1 换客户：数据完全隔离 ----------
    await page.getByTestId('logout').click()
    await expect(page.locator('form.login')).toBeVisible()
    const page2 = await browser.newPage()
    await page2.goto('/')
    await login(page2, OTHER.id, OTHER.pin)
    await page2.getByRole('button', { name: '订单', exact: true }).click()
    await expect(page2.locator('table.ledger tbody tr, p.empty').first()).toBeVisible()
    // 隔离的可见证据：1001 的订单在 2002 的账目里根本不存在。
    await expect(page2.locator(`td.order-no:text("${newOrder}")`)).toHaveCount(0)
    await snap(page2, 'US-6.1-另一客户_看不到他人订单.png')
    await page2.getByRole('button', { name: '助手', exact: true }).click()
    await snap(page2, 'US-6.1-另一客户_会话列表同样隔离.png')

    // ---------- US-6.2 换一个客户拿同一个 token：知道令牌也不算数 ----------
    const foreign = await page2.request.post(
      `/api/assistant/actions/${confirmedCard!.token}/confirm`,
      { headers: await xsrf(page2) },
    )
    console.log(`[story] US-6.2 他人 token 确认 → HTTP ${foreign.status()}`)
    expect(foreign.status()).toBe(403)

    // ---------- US-8.3 Swagger ----------
    await page2.goto('/swagger-ui.html')
    await page2.waitForSelector('.information-container, .opblock-tag', { timeout: 20_000 })
    await snap(page2, 'US-8.3-在线API文档.png')
  })
})
