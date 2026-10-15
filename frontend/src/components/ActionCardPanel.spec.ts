import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type PropType } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { api, ApiError } from '../api'
import type { ActionCard } from '../types'
import ActionCardPanel from './ActionCardPanel.vue'

// The card only ever talks to these three endpoints; everything else it renders is props. The real
// ApiError travels through so `instanceof` in the panel still holds -- a 410/409 is told apart by
// the error's code and status, not by its text.
vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ApiError: actual.ApiError,
    ACTION_EXPIRED: actual.ACTION_EXPIRED,
    api: { confirm: vi.fn(), reject: vi.fn(), reinitiate: vi.fn() },
  }
})

const confirmMock = vi.mocked(api.confirm)
const rejectMock = vi.mocked(api.reject)
const reinitiateMock = vi.mocked(api.reinitiate)

function card(overrides: Partial<ActionCard> = {}): ActionCard {
  return {
    token: 'tok-1',
    toolName: 'createOrder',
    title: '创建订单',
    summary: '创建订单：GADGET-02 × 2',
    argumentsJson: '{"lines":[{"sku":"GADGET-02","quantity":2}]}',
    fields: [
      { label: '商品', value: 'GADGET-02' },
      { label: '数量', value: '2' },
    ],
    confirmEndpoint: '/api/assistant/actions/tok-1/confirm',
    rejectEndpoint: '/api/assistant/actions/tok-1/reject',
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    status: 'PENDING',
    ...overrides,
  }
}

/**
 * Stands in for the assistant view: it owns the card and swaps it for whatever the resolution
 * says, which is what makes the "authorised cards stop being clickable" claim testable.
 */
const Host = defineComponent({
  props: { initial: { type: Object as PropType<ActionCard>, required: true } },
  setup(props) {
    const current = ref<ActionCard>(props.initial)
    return () =>
      h(ActionCardPanel, {
        card: current.value,
        onResolved: (next: ActionCard) => {
          current.value = next
        },
      })
  },
})

// Each test states the resolution it expects: `restoreMocks` already clears implementations
// between tests, and a manual `mockReset()` here would make Vitest report a caught rejection as an
// unhandled one.

describe('待授权的卡片', () => {
  it('把要执行的事、每个字段和有效期都摊开给客户看', () => {
    const wrapper = mount(ActionCardPanel, { props: { card: card() } })

    expect(wrapper.get('.slip-kind').text()).toBe('创建订单')
    expect(wrapper.get('.slip-state').text()).toBe('待授权')
    expect(wrapper.get('.slip-fields').text()).toContain('GADGET-02')
    expect(wrapper.get('.slip-fields').text()).toContain('2')
    expect(wrapper.get('.clock').text()).toBe('15 分钟后过期')
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['授权执行', '拒绝'])
  })

  it('不到一分钟时改口说「即将过期」', () => {
    const wrapper = mount(ActionCardPanel, {
      props: { card: card({ expiresAt: new Date(Date.now() + 30_000).toISOString() }) },
    })

    expect(wrapper.get('.clock').text()).toBe('即将过期')
  })

  it('倒计时自己往前走：一分钟的流逝就改口，不必等卡片数据更新', async () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
      const wrapper = mount(ActionCardPanel, {
        props: { card: card({ expiresAt: '2026-01-01T00:01:30Z' }) },
      })
      expect(wrapper.get('.clock').text()).toBe('2 分钟后过期')

      // 墙钟走了一分钟：节拍醒来重读时间，标签随之改口——卡片数据一动没动。
      vi.setSystemTime(new Date('2026-01-01T00:01:00Z'))
      await vi.advanceTimersByTimeAsync(30_000)

      expect(wrapper.get('.clock').text()).toBe('即将过期')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('授权就是那扇门', () => {
  it('授权后以服务端返回的状态收尾，第二颗确认按钮随即消失', async () => {
    confirmMock.mockResolvedValue({
      token: 'tok-1',
      status: 'CONFIRMED',
      resultJson: '{"orderNo":"ORD1"}',
      replayed: false,
    })
    const wrapper = mount(Host, { props: { initial: card() } })

    await wrapper.get('button.authority').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledWith('tok-1')
    expect(wrapper.get('.slip-state').text()).toBe('已执行')
    expect(wrapper.get('.slip').classes()).toContain('done')
    expect(wrapper.get('.stamp').classes()).toContain('land')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.text()).toContain('不会再执行')
  })

  it('拒绝同样只走服务端，卡片落到已拒绝', async () => {
    rejectMock.mockResolvedValue({
      token: 'tok-1',
      status: 'REJECTED',
      resultJson: null,
      replayed: false,
    })
    const wrapper = mount(Host, { props: { initial: card() } })

    await wrapper.get('button.quiet').trigger('click')
    await flushPromises()

    expect(rejectMock).toHaveBeenCalledWith('tok-1')
    expect(confirmMock).not.toHaveBeenCalled()
    expect(wrapper.get('.slip-state').text()).toBe('已拒绝')
    expect(wrapper.get('.slip').classes()).toContain('refused')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('服务端拒绝这次授权时如实报错，卡片留在待授权而不是假装成功', async () => {
    confirmMock.mockRejectedValue(new Error('确认单已过期'))
    const wrapper = mount(ActionCardPanel, { props: { card: card() } })

    await wrapper.get('button.authority').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]).toEqual(['确认单已过期'])
    expect(wrapper.get('.slip-state').text()).toBe('待授权')
    expect(wrapper.findAll('button')).toHaveLength(2)
  })

  it('410（卡已过期）不是报错而是请求重读：服务端已经改了这张卡，本地的待授权是旧的', async () => {
    confirmMock.mockRejectedValue(new ApiError('ACTION_EXPIRED', '确认单已过期', 410))
    const wrapper = mount(ActionCardPanel, { props: { card: card() } })

    await wrapper.get('button.authority').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('error')).toBeUndefined()
  })

  it('409（在别处已被处理）同理：请求重读，让服务端的新状态盖章', async () => {
    confirmMock.mockRejectedValue(new ApiError('CONFLICT', '这张确认单已经被处理过了', 409))
    const wrapper = mount(ActionCardPanel, { props: { card: card() } })

    await wrapper.get('button.authority').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('error')).toBeUndefined()
  })
})

describe('历史里回填的卡片', () => {
  const settled = [
    { status: 'CONFIRMED', label: '已执行', sign: '不会再执行' },
    { status: 'REJECTED', label: '已拒绝', sign: '未执行' },
  ] as const

  it.each(settled)('$status 只读呈现为「$label」，不留任何按钮', ({ status, label, sign }) => {
    const wrapper = mount(ActionCardPanel, { props: { card: card({ status }) } })

    expect(wrapper.get('.slip-state').text()).toBe(label)
    expect(wrapper.get('.stamp').text()).toBe(label)
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.text()).toContain(sign)
    // Only a card resolved in this session gets the stamp animation.
    expect(wrapper.get('.stamp').classes()).not.toContain('land')
  })

  it('EXPIRED 是唯一留了出路的死态：一颗「重新发起」，别无其他', () => {
    const wrapper = mount(ActionCardPanel, { props: { card: card({ status: 'EXPIRED' }) } })

    expect(wrapper.get('.slip-state').text()).toBe('已过期')
    expect(wrapper.get('.stamp').text()).toBe('已过期')
    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['重新发起'])
    expect(wrapper.text()).toContain('未执行')
    expect(wrapper.get('.stamp').classes()).not.toContain('land')
  })
})

describe('重新发起过期的卡片', () => {
  const expired = () => card({ status: 'EXPIRED' })

  it('点一下就请服务端把同一个提议重新摆出来，随后视图重读会话', async () => {
    reinitiateMock.mockResolvedValue({ card: { ...card(), token: 'tok-2' }, conversationId: 7 })
    const wrapper = mount(ActionCardPanel, { props: { card: expired() } })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(reinitiateMock).toHaveBeenCalledWith('tok-1')
    expect(wrapper.emitted('reinitiated')).toHaveLength(1)
    expect(wrapper.emitted('error')).toBeUndefined()
    // 本地这张仍旧是「已过期」：新卡片由服务端写进历史，视图重读后才上场。
    expect(wrapper.get('.slip-state').text()).toBe('已过期')
  })

  it('409（本地这张其实还活着或已在别处终结）请求重读，不当失败报', async () => {
    reinitiateMock.mockRejectedValue(new ApiError('CONFLICT', '这张确认单还活着', 409))
    const wrapper = mount(ActionCardPanel, { props: { card: expired() } })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('error')).toBeUndefined()
  })

  it('服务端真的出错时如实报错，按钮留着可以再点', async () => {
    reinitiateMock.mockRejectedValue(new Error('身份已失效'))
    const wrapper = mount(ActionCardPanel, { props: { card: expired() } })

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]).toEqual(['身份已失效'])
    expect(wrapper.findAll('button')).toHaveLength(1)
  })
})
