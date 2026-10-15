import { beforeEach, describe, expect, it, vi } from 'vitest'
import { stashDraft, takeDraft } from './draft'

/**
 * What this pins is the whole point of US-6.4's third acceptance line: an expired session sends the
 * customer back to the login page, and the sentence they had not sent yet must not be part of the
 * damage. The stash is keyed by customer, so it also has to hold the line the other way -- no
 * customer may ever find somebody else's half-written order.
 */
describe('没发出去的草稿', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('存进去再取回来：这句话跟着这个人活着', () => {
    stashDraft('1001', '帮我下单 2 个 GADGET-02')

    expect(takeDraft('1001')).toBe('帮我下单 2 个 GADGET-02')
  })

  it('按客户分开存：换个人登录看到的是自己的空白框', () => {
    stashDraft('1001', '帮我下单 2 个 GADGET-02')

    expect(takeDraft('2002')).toBe('')
  })

  it('草稿清空就是删掉记录，不留一条空值等着被读出来', () => {
    stashDraft('1001', '先写了一半')
    stashDraft('1001', '')

    expect(takeDraft('1001')).toBe('')
    expect(sessionStorage.length).toBe(0)
  })

  it('还没登录（没有客户号）时既不存也不取', () => {
    expect(takeDraft(undefined)).toBe('')
    expect(sessionStorage.length).toBe(0)
  })

  it('存储不可用（无痕模式等）时安静退化，不把输入框一起弄坏', () => {
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is blocked')
    })
    const readBlocked = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage is blocked')
    })

    expect(() => stashDraft('1001', '半句话')).not.toThrow()
    expect(takeDraft('1001')).toBe('')

    blocked.mockRestore()
    readBlocked.mockRestore()
  })
})
