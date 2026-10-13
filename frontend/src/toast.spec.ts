import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The toast is the only place the SPA tells a customer that something went wrong (a forbidden
 * order, a model that failed). Its two business rules: it says the error, and it goes away.
 */
describe('错误提示条', () => {
  let useToast: (typeof import('./toast'))['useToast']

  beforeEach(async () => {
    vi.resetModules()
    vi.useFakeTimers()
    ;({ useToast } = await import('./toast'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('推入一条消息后立即可见，并默认按错误样式呈现', () => {
    const toast = useToast()

    toast.push('无权访问该订单')

    expect(toast.current.value).toBe('无权访问该订单')
    expect(toast.isError.value).toBe(true)
  })

  it('五秒后自己消失，不需要用户手动关闭', () => {
    const toast = useToast()
    toast.push('请求失败')

    vi.advanceTimersByTime(4999)
    expect(toast.current.value).toBe('请求失败')

    vi.advanceTimersByTime(1)
    expect(toast.current.value).toBe('')
  })

  it('连续两条时以后一条为准，且计时重新开始', () => {
    const toast = useToast()
    toast.push('第一条')
    vi.advanceTimersByTime(4000)

    toast.push('第二条')
    vi.advanceTimersByTime(4999)

    // The first message's timer must not cut the second one short.
    expect(toast.current.value).toBe('第二条')
    vi.advanceTimersByTime(1)
    expect(toast.current.value).toBe('')
  })

  it('可以推一条非错误的提示', () => {
    const toast = useToast()

    toast.push('已执行', false)

    expect(toast.isError.value).toBe(false)
  })
})
