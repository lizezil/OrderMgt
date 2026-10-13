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

/** 与 AssistantView 里的对账等待间隔保持一致；改动那边记得同步这里。 */
const RECONCILE_DELAY_MS = 1500

/** 假计时器下的 flushPromises：VTU 内部走 setImmediate，靠推进时钟把它放出来。 */
async function flush() {
  await vi.advanceTimersByTimeAsync(1)
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

  it('服务端没收到这条消息时撤回乐观气泡、把草稿还给客户，并报出原因', async () => {
    vi.useFakeTimers()
    try {
      listConversationsMock.mockResolvedValue([])
      chatStreamMock.mockRejectedValue(new Error('上游模型超时'))
      // 历史里查无此消息 → 这条发送没有落地，本地状态可以安全回滚。
      historyMock.mockResolvedValue([])

      const wrapper = mount(AssistantView)
      await flush()
      await wrapper.get('textarea').setValue('帮我下单 1 个 GIZMO-03')
      await sendButton(wrapper).trigger('click')
      await flush()

      // 第一次查无不能定案：断线可能赶在 prepare 的提交落地之前，先等一轮再判。
      expect(historyMock).toHaveBeenCalledTimes(1)
      expect(wrapper.get('textarea').element.value).toBe('')

      await vi.advanceTimersByTimeAsync(RECONCILE_DELAY_MS)
      await flush()

      expect(historyMock).toHaveBeenCalledTimes(2)
      expect(wrapper.emitted('error')?.[0]).toEqual(['上游模型超时'])
      expect(wrapper.findAll('article')).toHaveLength(0)
      expect(wrapper.get('textarea').element.value).toBe('帮我下单 1 个 GIZMO-03')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('断流对账', () => {
  it('回复晚一步才落库时安静等它出现：以服务端为准，恢复后不惊动客户', async () => {
    vi.useFakeTimers()
    try {
      listConversationsMock.mockResolvedValue([])
      chatStreamMock.mockRejectedValue(new Error('连接中断'))
      const mine: Message = {
        id: 1,
        role: 'CUSTOMER',
        content: '我有哪些订单？',
        createdAt: '',
        action: null,
      }
      const reply: Message = {
        id: 2,
        role: 'ASSISTANT',
        content: '你有 3 张订单。',
        createdAt: '',
        action: null,
      }
      historyMock.mockResolvedValueOnce([mine]).mockResolvedValueOnce([mine, reply])

      const wrapper = mount(AssistantView)
      await flush()
      await wrapper.get('textarea').setValue('我有哪些订单？')
      await sendButton(wrapper).trigger('click')
      await flush()

      // 服务端还在写这一轮：只有客户消息，先等一轮再看。
      expect(historyMock).toHaveBeenCalledTimes(1)
      expect(wrapper.emitted('error')).toBeUndefined()

      await vi.advanceTimersByTimeAsync(RECONCILE_DELAY_MS)
      await flush()

      expect(historyMock).toHaveBeenCalledTimes(2)
      expect(wrapper.findAll('article')).toHaveLength(2)
      expect(wrapper.emitted('error')).toBeUndefined()
      expect(wrapper.get('textarea').element.value).toBe('')
    } finally {
      vi.useRealTimers()
    }
  })

  it('消息已入库但回复一直没来：保留气泡、不还草稿——服务端已有这条，重发会重复', async () => {
    vi.useFakeTimers()
    try {
      listConversationsMock.mockResolvedValue([])
      chatStreamMock.mockRejectedValue(new Error('上游模型超时'))
      historyMock.mockResolvedValue([
        {
          id: 1,
          role: 'CUSTOMER',
          content: '帮我下单 1 个 GIZMO-03',
          createdAt: '',
          action: null,
        },
      ])

      const wrapper = mount(AssistantView)
      await flush()
      await wrapper.get('textarea').setValue('帮我下单 1 个 GIZMO-03')
      await sendButton(wrapper).trigger('click')
      await flush()
      await vi.advanceTimersByTimeAsync(RECONCILE_DELAY_MS * 3)
      await flush()

      expect(historyMock).toHaveBeenCalledTimes(3)
      // 总数钉住半截的流式占位气泡也不能留下。
      expect(wrapper.findAll('article')).toHaveLength(1)
      expect(wrapper.get('textarea').element.value).toBe('')
      expect(wrapper.emitted('error')?.[0]).toEqual(['上游模型超时'])
    } finally {
      vi.useRealTimers()
    }
  })

  it('连历史都拉不到时保留本地气泡、不还草稿：拿不准有没有落地，报错但不诱导重发', async () => {
    listConversationsMock.mockResolvedValue([])
    chatStreamMock.mockRejectedValue(new Error('网络不通'))
    historyMock.mockRejectedValue(new Error('网络不通'))

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('我有哪些订单？')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    // 总数钉住半截的流式占位气泡也不能留下。
    expect(wrapper.findAll('article')).toHaveLength(1)
    expect(wrapper.get('textarea').element.value).toBe('')
    expect(wrapper.emitted('error')?.[0]).toEqual(['网络不通'])
  })

  it('重发一模一样的文字而服务端没有新落地：按未发送处理，不被旧同文消息冒充成已恢复', async () => {
    vi.useFakeTimers()
    try {
      const earlier: Message = {
        id: 1,
        role: 'CUSTOMER',
        content: '我有哪些订单？',
        createdAt: '',
        action: null,
      }
      const earlierReply: Message = {
        id: 2,
        role: 'ASSISTANT',
        content: '你有 3 张订单。',
        createdAt: '',
        action: null,
      }
      listConversationsMock.mockResolvedValue([conversation(7, '查询')])
      // 每次返回新数组：mock 复用同一实例的话，组件推进 messages 的乐观气泡会污染"服务端历史"。
      historyMock.mockImplementation(async () => [earlier, earlierReply]) // 新发送始终没有落地
      chatStreamMock.mockRejectedValue(new Error('连接中断'))

      const wrapper = mount(AssistantView)
      await flush()
      await wrapper.get('textarea').setValue('我有哪些订单？')
      await sendButton(wrapper).trigger('click')
      await flush()
      await vi.advanceTimersByTimeAsync(RECONCILE_DELAY_MS)
      await flush()

      // 旧的同文消息带 id 1 出现在 knownIds 里，不能被认领成本次发送的结果。
      expect(wrapper.emitted('error')?.[0]).toEqual(['连接中断'])
      expect(wrapper.get('textarea').element.value).toBe('我有哪些订单？')
      expect(wrapper.findAll('article')).toHaveLength(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('等待期间客户切走会话：不把这段对话的历史盖到新打开的会话上', async () => {
    vi.useFakeTimers()
    try {
      const mine: Message = {
        id: 1,
        role: 'CUSTOMER',
        content: '我有哪些订单？',
        createdAt: '',
        action: null,
      }
      const elsewhere: Message = {
        id: 9,
        role: 'ASSISTANT',
        content: '另一段对话的旧回复。',
        createdAt: '',
        action: null,
      }
      listConversationsMock.mockResolvedValue([conversation(7, '查询'), conversation(8, '另一段')])
      historyMock
        .mockResolvedValueOnce([]) // 打开会话 7：还没有消息
        .mockResolvedValueOnce([mine]) // 对账第 1 读：消息在、回复没来
        .mockResolvedValueOnce([elsewhere]) // 切到会话 8
        .mockResolvedValueOnce([mine]) // 对账第 2 读：晚到，但会话已经切走
      chatStreamMock.mockRejectedValue(new Error('连接中断'))

      const wrapper = mount(AssistantView)
      await flush()
      await wrapper.get('textarea').setValue('我有哪些订单？')
      await sendButton(wrapper).trigger('click')
      await flush()

      // 对账还在等下一轮，客户点开了另一段对话。
      const other = wrapper.findAll('.entry').find((entry) => entry.text().includes('另一段'))
      if (!other) throw new Error('找不到另一段对话的入口')
      await other.trigger('click')
      await flush()

      await vi.advanceTimersByTimeAsync(RECONCILE_DELAY_MS)
      await flush()

      expect(wrapper.text()).toContain('另一段对话的旧回复。')
      expect(wrapper.text()).not.toContain('我有哪些订单？')
      expect(wrapper.emitted('error')?.[0]).toEqual(['连接中断'])
      expect(wrapper.get('textarea').element.value).toBe('')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('输入框归属的会话，由正在切换的那一步决定', () => {
  it('新对话还在创建时就回车：这一句属于新会话，不落进刚离开的那条', async () => {
    listConversationsMock.mockResolvedValue([conversation(1, '第一条')])
    let resolveCreation!: (conversation: Conversation) => void
    startConversationMock.mockImplementation(
      () =>
        new Promise<Conversation>((resolve) => {
          resolveCreation = resolve
        }),
    )

    const wrapper = await mountView()
    const newConversation = wrapper
      .findAll('button')
      .find((candidate) => candidate.text().includes('新对话'))
    if (!newConversation) throw new Error('找不到新对话按钮')
    await newConversation.trigger('click') // POST /conversations 还挂着
    await wrapper.get('textarea').setValue('第二条的问题')
    await sendButton(wrapper).trigger('click') // 竞态窗口：此刻 activeId 还是旧会话
    resolveCreation(conversation(2, null))
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledWith('第二条的问题', 2, expect.any(Function))
  })

  it('切回助手后首屏会话还没展开就回车：这句落在即将展开的那条里，不另起炉灶', async () => {
    let resolveList!: (conversations: Conversation[]) => void
    listConversationsMock.mockImplementation(
      () =>
        new Promise<Conversation[]>((resolve) => {
          resolveList = resolve
        }),
    )

    const wrapper = mount(AssistantView)
    await nextTick()
    await wrapper.get('textarea').setValue('回来后的第一句')
    await sendButton(wrapper).trigger('click') // 竞态窗口：加载还没落定，activeId 为空
    resolveList([conversation(3, '之前聊的')])
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledWith('回来后的第一句', 3, expect.any(Function))
    // 而不是绕过加载、自作主张新建一段（startConversation 默认给的是 7）。
    expect(startConversationMock).not.toHaveBeenCalled()
  })

  it('上一轮收尾中（侧栏刷新一直挂着）的回车不被吞掉：下一句照常发出', async () => {
    listConversationsMock.mockResolvedValueOnce([]) // 挂载时的列表
    listConversationsMock.mockImplementation(() => new Promise(() => {})) // 之后的刷新永不返回
    chatStreamMock.mockResolvedValue({
      conversationId: 7,
      reply: '好的',
      executed: [],
      actions: [],
      promptTokens: 1,
      completionTokens: 1,
      model: 'offline-mock',
      latencyMs: 10,
    })

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('第一句')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    await wrapper.get('textarea').setValue('第二句')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledTimes(2)
    expect(chatStreamMock.mock.calls[1]![0]).toBe('第二句')
  })
})
