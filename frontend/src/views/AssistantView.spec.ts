import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { ActionCard, ChatResponse, Conversation, Message } from '../types'
import AssistantView from './AssistantView.vue'

vi.mock('../api', () => ({
  api: {
    listConversations: vi.fn(),
    history: vi.fn(),
    startConversation: vi.fn(),
    chatStream: vi.fn(),
    confirm: vi.fn(),
    reject: vi.fn(),
  },
}))

const listConversationsMock = vi.mocked(api.listConversations)
const historyMock = vi.mocked(api.history)
const startConversationMock = vi.mocked(api.startConversation)
const chatStreamMock = vi.mocked(api.chatStream)

function conversation(id: number, title: string | null): Conversation {
  const at = '2026-01-01T00:00:00'
  return { id, title, createdAt: at, updatedAt: at, lastMessageAt: at }
}

function pendingCard(): ActionCard {
  return {
    token: 'tok-1',
    toolName: 'createOrder',
    title: '创建订单',
    summary: '创建订单：GADGET-02 × 2',
    argumentsJson: '{"lines":[{"sku":"GADGET-02","quantity":2}]}',
    fields: [{ label: '商品', value: 'GADGET-02' }],
    confirmEndpoint: '/api/assistant/actions/tok-1/confirm',
    rejectEndpoint: '/api/assistant/actions/tok-1/reject',
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    status: 'PENDING',
  }
}

function sendButton(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text() === '发送')
  if (!button) throw new Error('找不到发送按钮')
  return button
}

async function mountView() {
  const wrapper = mount(AssistantView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  startConversationMock.mockResolvedValue(conversation(7, null))
  historyMock.mockResolvedValue([])
})

describe('打开助手', () => {
  it('挂载时列出会话并展开最近的一条', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '我有哪些订单？')])
    historyMock.mockResolvedValue([
      { id: 1, role: 'CUSTOMER', content: '我有哪些订单？', createdAt: '', action: null },
    ])

    const wrapper = await mountView()

    expect(historyMock).toHaveBeenCalledWith(7)
    expect(wrapper.get('.entry.active').text()).toContain('我有哪些订单？')
    expect(wrapper.get('article.customer').text()).toContain('你')
  })

  it('一段对话都没有时，直接给出可以照着说的第一句', async () => {
    listConversationsMock.mockResolvedValue([])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('还没有对话')
    expect(wrapper.text()).toContain('帮我下单 2 个 GADGET-02')
    expect(historyMock).not.toHaveBeenCalled()
  })

  it('以方括号开头的行按机器输出排版，其余行按散文', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '查询')])
    historyMock.mockResolvedValue([
      {
        id: 1,
        role: 'ASSISTANT',
        content: '我查到了这些：\n[queryMyOrders] {"total":0}',
        createdAt: '',
        action: null,
      } as Message,
    ])

    const wrapper = await mountView()

    expect(wrapper.findAll('code.data').map((code) => code.text())).toEqual([
      '[queryMyOrders] {"total":0}',
    ])
  })
})

describe('流式回复', () => {
  it('回复边到边渲染，落定后换成服务端持久化的那一轮并附上卡片', async () => {
    listConversationsMock.mockResolvedValue([])
    let emitDelta!: (delta: string) => void
    let finish!: (response: ChatResponse) => void
    chatStreamMock.mockImplementation((_message, _id, onToken) => {
      emitDelta = onToken
      return new Promise<ChatResponse>((resolve) => {
        finish = resolve
      })
    })

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('帮我下单 2 个 GADGET-02')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(startConversationMock).toHaveBeenCalled()
    expect(chatStreamMock).toHaveBeenCalledWith('帮我下单 2 个 GADGET-02', 7, expect.any(Function))

    emitDelta('我准备好')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好')

    emitDelta('了一个下单操作，请你确认。')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好了一个下单操作，请你确认。')

    finish({
      conversationId: 7,
      reply: '我准备好了一个下单操作，请你确认。',
      executed: [],
      actions: [pendingCard()],
      promptTokens: 12,
      completionTokens: 8,
      model: 'offline-mock',
      latencyMs: 321,
    })
    await flushPromises()

    // The streamed placeholder is replaced (not appended to), and the card is its own turn.
    expect(wrapper.findAll('article.assistant').map((line) => line.get('.said').text())).toEqual([
      '我准备好了一个下单操作，请你确认。',
      '创建订单：GADGET-02 × 2',
    ])
    expect(wrapper.get('.slip-state').text()).toBe('待授权')
    expect(wrapper.get('.meter').text()).toContain('本轮 prompt 12 / completion 8 tokens')
    expect(wrapper.get('.meter').text()).toContain('模型耗时 321 ms')
  })

  it('上游失败时撤回乐观气泡、把草稿还给客户，并报出原因', async () => {
    listConversationsMock.mockResolvedValue([])
    chatStreamMock.mockRejectedValue(new Error('上游模型超时'))

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('帮我下单 1 个 GIZMO-03')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]).toEqual(['上游模型超时'])
    expect(wrapper.findAll('article')).toHaveLength(0)
    expect(wrapper.get('textarea').element.value).toBe('帮我下单 1 个 GIZMO-03')
  })
})
