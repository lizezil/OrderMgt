import { execSync } from 'node:child_process'
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
 *
 * Three of the newer stories need a word on how they are (or are not) walked here:
 * - US-1.11 has no step at all: a model that goes off-script cannot be summoned from a browser,
 *   so its deterministic wrapping is pinned in backend tests; faking one here would be theatre.
 * - US-1.13's referent questions need the real model -- the grounding block is what lets it
 *   answer. The stub never reads prompts and replies with its own fallback; the step detects
 *   that, logs it, and skips the strict assertion (the injection itself is pinned in tests).
 * - US-1.12 waits for no sweeper: it runs the sweeper's own UPDATE by hand through docker exec
 *   (the TTL is 15 minutes; a walk should not sit through it). The statement writes exactly what
 *   the sweeper writes when a deadline passes, and touches nothing else on the card.
 */
const OUT_DIR = path.resolve(
  fileURLToPath(new URL('.', import.meta.url)),
  '..',
  '..',
  'story-screenshots',
)
fs.mkdirSync(OUT_DIR, { recursive: true })
// A run mirrors the walk exactly: last run's shots are removed first, so a renumbered or removed
// step cannot leave an orphan PNG behind to masquerade as current evidence.
for (const name of fs.readdirSync(OUT_DIR)) {
  if (name.endsWith('.png')) fs.rmSync(path.join(OUT_DIR, name))
}

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

/**
 * Runs one statement through the resident MySQL container's own client -- credentials come from
 * the container's environment (compose puts MYSQL_ROOT_PASSWORD/MYSQL_DATABASE there), so nothing
 * is baked into this file. The one place a walk has to move server state the browser cannot reach
 * (US-1.12's TTL).
 */
function mysql(sql: string): void {
  execSync(
    `docker exec -i ordermgt-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'`,
    { input: sql },
  )
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
    const slipsBeforeCancel = await page.locator('figure.slip').count()
    await ask(page, `取消 ${newOrder}`)
    // 真实模型偶尔先花一整轮去查这张单（一次只读调用就占满一轮；离线桩从不这样，它总直接提议）。
    // 这多样性不是走查要消灭的，而是它要如实穿过的：若这一轮没给出卡片，就顺着补一句确认——
    // 顾客本来也会这么说——再等提议。
    if ((await page.locator('figure.slip').count()) === slipsBeforeCancel) {
      await ask(page, `对，取消 ${newOrder}`)
    }
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

    // 频控换窗口：assistant.ai.rate-limit 每客户每分钟准入 6 条发送（滑动窗口）。这条走查把两次
    // 额度都贴着 6 用满——上一段：我有哪些订单、2 个 GADGET-02、1 个 WIDGET-01、取消、真实模型
    // 走样时补的那句「对，取消…」、最近一次下单，至多 6 条；下一段：退款、三张提议、两次指代
    // 问答，恰好 6 条。6 是额度不是护栏，贴满全部准入；会 429 的只有第 7 条，所以这里停一拍让
    // 上一段整段滑出 60 秒窗口，下一段走一份新预算。这不是绕开频控——是照产品自己的节拍走路。
    await page.waitForTimeout(65_000)

    // ---------- US-1.10 能力之外的问题：如实说做不了，不给卡片、不假装 ----------
    // 离线桩对「退款」有一条排在最前面的台词（OfflinePrompts.OUT_OF_CAPABILITY），真实模型的
    // 措辞是它自己的——所以断言只钉住结构：有一句能读的回复、没有冒出卡片。编造过程或假装
    // 执行都会在这里现形。
    await ask(page, '帮我把最近这个订单退款')
    expect(
      (await assistantTurn(page).innerText()).trim().length,
      'US-1.10: 要有一句听得懂的话',
    ).toBeGreaterThan(0)
    await expect(page.locator('figure.slip'), 'US-1.10: 能力之外不产生提议卡片').toHaveCount(0)
    await snap(page, 'US-1.10-能力之外的请求_如实说做不了.png')

    // US-1.11（模型走样时服务端兜底）在这里没有步骤：白名单外的工具名、或一字不答的走样，
    // 只有模型真的走样一次才触发，浏览器里叫不出来。那句固定兜底话术由后端测试确定性钉住
    // （见 docs/用户故事.md 的 US-1.11），这份走查不替它伪造一场走样。

    // ---------- US-1.13 多张待确认卡片并存：「刚才那个」/「第二张」指得准 ----------
    // 按故事自己的脚本：先提议三张、一张都不确认，再问指代。
    await ask(page, '帮我下单 2 个 GADGET-02')
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await ask(page, '帮我下单 1 个 DOODAD-04')
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await ask(page, '帮我下单 3 个 WIDGET-01')
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await expect(slip(page)).toContainText('WIDGET-01')

    await ask(page, '刚才那个是什么')
    const referent = (await assistantTurn(page).innerText()).replace(/\s+/g, ' ')
    if (referent.includes('（离线模式）')) {
      // 桩不读提示词，没有 grounding 可演示，它自己那条兜底台词就是证据；强断言留给真实模型。
      console.log(`[story] US-1.13: 离线桩作答「${referent.slice(0, 30)}…」，跳过指代断言`)
    } else {
      // 断言的是「模型看得到事实」：grounding 注入了三张卡片的清单，它才答得出"刚才那个"
      // 是 WIDGET-01 那张、第二张是 DOODAD-04 那张。注入本身的确定性由后端测试钉住。
      expect(referent, 'US-1.13: 「刚才那个」= 最后提议那张（WIDGET-01）').toContain('WIDGET-01')
      await ask(page, '第二张是什么')
      const second = (await assistantTurn(page).innerText()).replace(/\s+/g, ' ')
      expect(second, 'US-1.13: 「第二张」= 提议顺序的第二张（DOODAD-04）').toContain('DOODAD-04')
    }
    await snap(page, 'US-1.13-三张卡片并存_刚才那个与第二张.png')

    // ---------- US-1.12 过期卡片一键重新发起 ----------
    // 等满 15 分钟的 TTL 不是走查该干的事：把最新那张待授权卡片按清扫器的口径拍死——
    // status=EXPIRED、resolved_at 落时间戳、deadline 拨回一小时前。清扫器每分钟对过期卡
    // 做的正是这一件事，这里只是不等它排到；卡片本身（参数、摘要、token）一点不碰。
    const slipsAlive = await page.locator('figure.slip').count()
    mysql(
      "UPDATE pending_actions SET status='EXPIRED', resolved_at=NOW(3), expires_at=NOW(3) - INTERVAL 1 HOUR " +
        "WHERE customer_id = 1001 AND status='PENDING' AND tool_name='createOrder' ORDER BY id DESC LIMIT 1",
    )
    // 重开当前会话（点侧栏高亮那一行），历史重读：最新一张卡片盖章「已过期」，多出重新发起入口。
    await page.locator('ol.docket button.entry.active').click()
    await expect(slip(page).locator('.slip-state')).toHaveText('已过期', { timeout: 10_000 })
    await expect(slip(page).getByRole('button', { name: '重新发起' })).toBeVisible()
    // 卡片在会话中部，而视口停在会话末尾——断言不挑位置，截图挑：把过期卡顶到上沿再拍。
    await slip(page).evaluate((el) => el.scrollIntoView({ block: 'start' }))
    await snap(page, 'US-1.12-过期卡上的重新发起入口.png')

    await slip(page).getByRole('button', { name: '重新发起' }).click()
    // 新卡片像一次新提议那样进历史；旧卡原地保持已过期——历史不删不改（ADR-0013 的立场）。
    await expect(page.locator('figure.slip')).toHaveCount(slipsAlive + 1)
    await expect(slip(page).locator('.slip-state')).toHaveText('待授权')
    await expect(slip(page), 'US-1.12: 重新发起的是同一件事（WIDGET-01 × 3）').toContainText(
      'WIDGET-01',
    )
    await expect(
      page
        .locator('figure.slip')
        .nth(slipsAlive - 1)
        .locator('.slip-state'),
      'US-1.12: 旧卡仍在原处，保持已过期',
    ).toHaveText('已过期')
    // 旧卡与新卡隔着两轮指代问答，960 的视口装不下；这一格拉高视口、旧卡顶到上沿，让
    // 「新卡待授权、旧卡保持已过期」同框——断言是给机器的，截图是给人的，对得上才对。
    await page.setViewportSize({ width: 1440, height: 1700 })
    await page
      .locator('figure.slip')
      .nth(slipsAlive - 1)
      .evaluate((el) => el.scrollIntoView({ block: 'start' }))
    await snap(page, 'US-1.12-重新发起后_新卡待授权旧卡保持已过期.png')
    await page.setViewportSize({ width: 1440, height: 960 })

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
