import { describe, expect, it } from 'vitest'
import { errorMessage } from './errors'

describe('errorMessage', () => {
  it('takes the message from a real Error', () => {
    expect(errorMessage(new Error('boom'), 'fallback')).toBe('boom')
  })

  it('falls back when the thrown value is not an Error', () => {
    expect(errorMessage('oops', '加载失败')).toBe('加载失败')
    expect(errorMessage(undefined, '加载失败')).toBe('加载失败')
    expect(errorMessage({ code: 500 }, '加载失败')).toBe('加载失败')
  })
})
