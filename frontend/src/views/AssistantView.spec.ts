import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, QUEUE_DROPPED, RUN_CANCELLED } from '../api'
import type { RunStreamHandlers, SessionStreamHandlers } from '../api'
import { RECONCILE_DELAY_MS } from '../constants'
import type {
  ActionCard,
  ChatResponse,
  Conversation,
  ConversationState,
  ConversationStatus,
  Message,
  QueuedTurn,
} from '../types'
import { useSessionStore } from '../store'
import AssistantView from './AssistantView.vue'

vi.mock('../api', async () => {
  // The real ApiError class travels through the mock so `instanceof` in the view still holds: the
  // dropped-queue path is detected by the error's code, not by its text.
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ApiError: actual.ApiError,
    QUEUE_DROPPED: actual.QUEUE_DROPPED,
    RUN_CANCELLED: actual.RUN_CANCELLED,
    ACTION_EXPIRED: actual.ACTION_EXPIRED,
    api: {
      listConversations: vi.fn(),
      history: vi.fn(),
      startConversation: vi.fn(),
      archiveConversation: vi.fn(),
      restoreConversation: vi.fn(),
      chatStream: vi.fn(),
      // The status stream never settles here: the tests drive the sidebar through the REST list and
      // the chat stream, not through pushes.
      eventsStream: vi.fn(() => new Promise<void>(() => {})),
      runStream: vi.fn(() => new Promise<void>(() => {})),
      cancelQueued: vi.fn(),
      stopRun: vi.fn(),
      confirm: vi.fn(),
      reject: vi.fn(),
    },
  }
})

const listConversationsMock = vi.mocked(api.listConversations)
const historyMock = vi.mocked(api.history)
const startConversationMock = vi.mocked(api.startConversation)
const chatStreamMock = vi.mocked(api.chatStream)
const cancelQueuedMock = vi.mocked(api.cancelQueued)
const stopRunMock = vi.mocked(api.stopRun)
const eventsStreamMock = vi.mocked(api.eventsStream)
const runStreamMock = vi.mocked(api.runStream)
const archiveConversationMock = vi.mocked(api.archiveConversation)
const restoreConversationMock = vi.mocked(api.restoreConversation)

function conversation(
  id: number,
  title: string | null,
  state: ConversationState = 'IDLE',
  runId: number | null = null,
  status: ConversationStatus = 'ACTIVE',
): Conversation {
  const at = '2026-01-01T00:00:00'
  return {
    id,
    title,
    createdAt: at,
    updatedAt: at,
    lastMessageAt: at,
    status,
    state,
    runId,
  }
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

/** The `done` payload for a turn that answered in prose; override what the test cares about. */
function reply(overrides: Partial<ChatResponse> = {}): ChatResponse {
  return {
    conversationId: 7,
    reply: '好的',
    executed: [],
    actions: [],
    promptTokens: 1,
    completionTokens: 1,
    model: 'offline-mock',
    latencyMs: 10,
    ...overrides,
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

/** 假计时器下的 flushPromises：VTU 内部走 setImmediate，靠推进时钟把它放出来。 */
async function flush() {
  await vi.advanceTimersByTimeAsync(1)
}

beforeEach(() => {
  vi.clearAllMocks()
  // The store is a module singleton: without this, one test's conversations leak into the next.
  useSessionStore().reset()
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

  it('store 里已有激活会话（切页回来）时，挂载回到那一条而不是最新的一条', async () => {
    listConversationsMock.mockResolvedValue([
      conversation(7, '最新的一段'),
      conversation(9, '我正在看的'),
    ])
    // beforeEach 的 reset 已把 store 清空；这里模拟切页前正看着 9 的残留态。
    useSessionStore().activeId.value = 9

    const wrapper = await mountView()

    expect(historyMock).toHaveBeenCalledWith(9)
    expect(wrapper.get('.entry.active').text()).toContain('我正在看的')
  })

  it('会话列表还在路上时侧栏说「加载中」，不谎报「还没有对话」', async () => {
    let settle: (value: Conversation[]) => void = () => {}
    listConversationsMock.mockImplementation(
      () => new Promise<Conversation[]>((resolve) => (settle = resolve)),
    )

    const wrapper = mount(AssistantView)
    await flushPromises()

    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.text()).not.toContain('还没有对话')

    settle([])
    await flushPromises()
    expect(wrapper.text()).toContain('还没有对话')
    expect(wrapper.text()).not.toContain('加载中')
  })

  it('一段对话都没有时，直接给出可以照着说的第一句', async () => {
    listConversationsMock.mockResolvedValue([])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('还没有对话')
    expect(wrapper.text()).toContain('帮我下单 2 个 GADGET-02')
    expect(historyMock).not.toHaveBeenCalled()
  })

  it('订单回复按散文与行内代码呈现，不再有机器文本块', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '查询')])
    historyMock.mockResolvedValue([
      {
        id: 1,
        role: 'ASSISTANT',
        content:
          '你名下有 1 张订单：\n\n`ORD-1` · 已创建 · ¥19.90\n- `WIDGET-01` Widget × 1 = ¥19.90',
        createdAt: '',
        action: null,
      } as Message,
    ])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('你名下有 1 张订单')
    expect(wrapper.findAll('article.assistant .said code').map((code) => code.text())).toEqual([
      'ORD-1',
      'WIDGET-01',
    ])
    expect(wrapper.find('pre.md-machine').exists()).toBe(false)
  })
})

describe('一轮的排版', () => {
  it('回复按 markdown 落成元素：标题、列表、行内代码各归其位', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '渲染')])
    historyMock.mockResolvedValue([
      {
        id: 1,
        role: 'ASSISTANT',
        content: '## 你有 2 张订单\n\n- ORD1 `99.00`\n- **ORD2** 待确认',
        createdAt: '',
        action: null,
      } as Message,
    ])

    const wrapper = await mountView()

    const said = wrapper.get('article.assistant .said')
    expect(said.get('h2').text()).toBe('你有 2 张订单')
    expect(said.findAll('li').map((item) => item.text())).toEqual(['ORD1 99.00', 'ORD2 待确认'])
    expect(said.get('li code').text()).toBe('99.00')
    expect(said.get('li strong').text()).toBe('ORD2')
  })

  it('客户的话走同一层渲染，标记不会原样贴到屏幕上', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([
      {
        id: 1,
        role: 'CUSTOMER',
        content: '帮我下单 `GADGET-02` 两个，别动 <b>ORD1</b>',
        createdAt: '',
        action: null,
      } as Message,
    ])

    const wrapper = await mountView()

    const said = wrapper.get('article.customer .said')
    expect(said.get('code').text()).toBe('GADGET-02')
    // Escaped, not parsed: a customer cannot inject an element with their own words.
    expect(said.find('b').exists()).toBe(false)
    expect(said.text()).toContain('别动 <b>ORD1</b>')
  })
})

describe('侧栏状态徽标', () => {
  it('只有生成中与待确认显示徽标，安静（IDLE）的会话不显示', async () => {
    listConversationsMock.mockResolvedValue([
      conversation(1, '待我确认的', 'WAITING_APPROVAL'),
      conversation(2, '正在生成的', 'RUNNING'),
      conversation(3, '安静的', 'IDLE'),
    ])

    const wrapper = await mountView()

    expect(wrapper.findAll('.entry-state').map((badge) => badge.text())).toEqual([
      '待确认',
      '生成中…',
    ])
  })
})

describe('有问无答的回合', () => {
  function unanswered(): Message {
    return {
      id: 1,
      role: 'CUSTOMER',
      content: '帮我下单 1 个 GIZMO-03',
      createdAt: '',
      action: null,
    }
  }

  it('历史停在一条没有回复的客户话上：看得见「有问无答」，并给出重试入口', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([unanswered()])

    const wrapper = await mountView()

    expect(wrapper.get('article.customer').text()).toContain('帮我下单 1 个 GIZMO-03')
    expect(wrapper.get('.retry').text()).toContain('没有收到回复')
  })

  it('重试把那句话作为全新的一轮重发', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([unanswered()])
    chatStreamMock.mockResolvedValue(reply())

    const wrapper = await mountView()
    await wrapper.get('.retry button').trigger('click')
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledWith(
      '帮我下单 1 个 GIZMO-03',
      7,
      expect.objectContaining({ onToken: expect.any(Function) }),
      expect.any(AbortSignal),
    )
  })

  it('回合还在跑（RUNNING）时不给重试：回复也许只是迟到，重发会多开一个 Run', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单', 'RUNNING')])
    historyMock.mockResolvedValue([unanswered()])

    const wrapper = await mountView()

    expect(wrapper.find('.retry').exists()).toBe(false)
  })

  it('已经有回复的回合不给重试入口', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([
      unanswered(),
      { id: 2, role: 'ASSISTANT', content: '好的', createdAt: '', action: null },
    ])

    const wrapper = await mountView()

    expect(wrapper.find('.retry').exists()).toBe(false)
  })
})

describe('流式回复', () => {
  it('回复边到边渲染，落定后换成服务端持久化的那一轮并附上卡片', async () => {
    listConversationsMock.mockResolvedValue([])
    let emitDelta!: (delta: string) => void
    let finish!: (response: ChatResponse) => void
    chatStreamMock.mockImplementation((_message, _id, handlers) => {
      emitDelta = handlers.onToken
      return new Promise<ChatResponse>((resolve) => {
        finish = resolve
      })
    })

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('帮我下单 2 个 GADGET-02')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(startConversationMock).toHaveBeenCalled()
    expect(chatStreamMock).toHaveBeenCalledWith(
      '帮我下单 2 个 GADGET-02',
      7,
      expect.objectContaining({ onToken: expect.any(Function) }),
      expect.any(AbortSignal),
    )

    emitDelta('我准备好')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好')

    emitDelta('了一个下单操作，请你确认。')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好了一个下单操作，请你确认。')

    finish(
      reply({
        reply: '我准备好了一个下单操作，请你确认。',
        actions: [pendingCard()],
        promptTokens: 12,
        completionTokens: 8,
        latencyMs: 321,
      }),
    )
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

  it('首字还没到时气泡显示输入指示，而不是一个空框', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    let emitDelta!: (delta: string) => void
    chatStreamMock.mockImplementation((_message, _id, handlers) => {
      emitDelta = handlers.onToken
      return new Promise<ChatResponse>(() => {})
    })

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('帮我下单 2 个 GADGET-02')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    // 气泡已经在位，但一个字都还没有：三个点占着位置，不留下空白的框。
    const pending = wrapper.get('article.assistant .said.pending')
    expect(pending.findAll('.dot')).toHaveLength(3)
    expect(pending.text()).toBe('')

    emitDelta('好的，我先核对库存。')
    await nextTick()

    expect(wrapper.find('.said.pending').exists()).toBe(false)
    expect(wrapper.get('article.assistant .said').text()).toBe('好的，我先核对库存。')
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

  it('发送中离开这一页：在飞的流被中止，不留一条还挂着的连接', async () => {
    listConversationsMock.mockResolvedValue([])
    let observedSignal: AbortSignal | undefined
    chatStreamMock.mockImplementation((_message, _id, _handlers, signal) => {
      observedSignal = signal
      return new Promise<ChatResponse>(() => {})
    })

    const wrapper = mount(AssistantView)
    await flushPromises()
    await wrapper.get('textarea').setValue('发送中离场')
    await sendButton(wrapper).trigger('click')
    await flushPromises()

    expect(observedSignal?.aborted).toBe(false)
    wrapper.unmount()
    expect(observedSignal?.aborted).toBe(true)
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

    expect(chatStreamMock).toHaveBeenCalledWith(
      '第二条的问题',
      2,
      expect.objectContaining({ onToken: expect.any(Function) }),
      expect.any(AbortSignal),
    )
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

    expect(chatStreamMock).toHaveBeenCalledWith(
      '回来后的第一句',
      3,
      expect.objectContaining({ onToken: expect.any(Function) }),
      expect.any(AbortSignal),
    )
    // 而不是绕过加载、自作主张新建一段（startConversation 默认给的是 7）。
    expect(startConversationMock).not.toHaveBeenCalled()
  })

  it('上一轮收尾中（侧栏刷新一直挂着）的回车不被吞掉：下一句照常发出', async () => {
    listConversationsMock.mockResolvedValueOnce([]) // 挂载时的列表
    listConversationsMock.mockImplementation(() => new Promise(() => {})) // 之后的刷新永不返回
    chatStreamMock.mockResolvedValue(reply())

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

  it('第一句的会话还在创建时就发第二句：两句同属一条会话，第二句排队而不是另起炉灶', async () => {
    listConversationsMock.mockResolvedValue([])
    // 第一次 POST 挂着不回；若第二句又自建会话，兜底的第二次会给它另一条（id 8）。
    let resolveCreation!: (conversation: Conversation) => void
    startConversationMock
      .mockImplementationOnce(
        () =>
          new Promise<Conversation>((resolve) => {
            resolveCreation = resolve
          }),
      )
      .mockResolvedValue(conversation(8, null))
    chatStreamMock.mockImplementation(() => new Promise<ChatResponse>(() => {}))

    const wrapper = await mountView()
    await wrapper.get('textarea').setValue('第一句')
    await sendButton(wrapper).trigger('click') // POST /conversations 还挂着
    await wrapper.get('textarea').setValue('第二句')
    await sendButton(wrapper).trigger('click') // 竞态窗口：第二句不得自建会话
    resolveCreation(conversation(7, null))
    await flushPromises()

    // 自建只发生一次，两条流都打向它——第二句随后由服务端排进同一条队，而不是两答同时流式输出。
    expect(startConversationMock).toHaveBeenCalledTimes(1)
    expect(chatStreamMock).toHaveBeenCalledTimes(2)
    expect(chatStreamMock.mock.calls.map((call) => call[1])).toEqual([7, 7])
  })

  it('切换未落定的窗口里连按两次回车：只发出一条，不把同一句复读进队伍', async () => {
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
    const textarea = wrapper.get('textarea')
    await textarea.setValue('只应发出一条')
    // 竞态窗口里的双击：输入框在第一次回车就该被清空并占忙，第二次没有词可发。
    await textarea.trigger('keydown', { key: 'Enter' })
    await textarea.trigger('keydown', { key: 'Enter' })
    resolveCreation(conversation(2, null))
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledTimes(1)
  })
})

describe('中文输入法的回车', () => {
  it('回车只是把英文上屏（组合中）：不发送，草稿原样保留', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '闲聊')])
    chatStreamMock.mockResolvedValue(reply())

    const wrapper = await mountView()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('帮我查一下 ORD')
    // 输入法组合中的 Enter：key 是 Enter 但 isComposing 为真（IME 上屏/确认候选）。
    await textarea.trigger('keydown', { key: 'Enter', isComposing: true })
    await flushPromises()

    expect(chatStreamMock).not.toHaveBeenCalled()
    expect(textarea.element.value).toBe('帮我查一下 ORD')
  })

  it('组合结束后的普通回车照常发送', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '闲聊')])
    chatStreamMock.mockResolvedValue(reply())

    const wrapper = await mountView()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('帮我查一下 ORD1')
    await textarea.trigger('keydown', { key: 'Enter', isComposing: false })
    await flushPromises()

    expect(chatStreamMock).toHaveBeenCalledTimes(1)
  })
})

describe('忙时入队', () => {
  /** The callbacks the view hands a stream; all of them are passed here. */
  interface Handlers {
    onToken: (delta: string) => void
    onQueued: (turn: QueuedTurn) => void
    onStarted: () => void
  }

  /**
   * A conversation that is already answering. Every stream stays open until the test settles it,
   * which is what makes "a second send while the first is still running" reachable.
   */
  function busyConversation() {
    const seen: Handlers[] = []
    const settlers: {
      resolve: (response: ChatResponse) => void
      reject: (error: unknown) => void
    }[] = []
    chatStreamMock.mockImplementation((_message, _id, handlers) => {
      seen.push(handlers as Handlers)
      return new Promise<ChatResponse>((resolve, reject) => {
        settlers.push({ resolve, reject })
      })
    })
    return {
      on: (index: number): Handlers => seen[index]!,
      reject: (index: number, error: unknown): void => settlers[index]!.reject(error),
      resolve: (index: number, response: ChatResponse): void => settlers[index]!.resolve(response),
    }
  }

  async function sendLine(wrapper: ReturnType<typeof mount>, text: string): Promise<void> {
    await wrapper.get('textarea').setValue(text)
    await sendButton(wrapper).trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([])
  })

  it('一轮还在回答时再发一句：不另起一个 Run，而是排进这条会话的队伍', async () => {
    const stream = busyConversation()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')

    // 忙，但发送键照旧可用（只要有话可说）：下一句就是要排进去的那句，而不是被悄悄吞掉的那句。
    await wrapper.get('textarea').setValue('第二句')
    expect(sendButton(wrapper).attributes('disabled')).toBeUndefined()

    await sendButton(wrapper).trigger('click')
    await flushPromises()
    stream.on(1).onQueued({ itemId: 'q1', position: 1 })
    await nextTick()

    expect(wrapper.get('.queue-head').text()).toBe('排队中：1 条')
    expect(wrapper.get('.queue-text').text()).toBe('第二句')
  })

  it('排队中的第二句只留在排队条里，不进会话区；轮到它开始才进对话', async () => {
    const stream = busyConversation()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')
    stream.on(1).onQueued({ itemId: 'q1', position: 1 })
    await nextTick()

    // 排队条里看得见它 —— 那里才是它在等的家。
    expect(wrapper.get('.queue-text').text()).toBe('第二句')
    // 会话区里还没有它：它没轮到，也不是助手正在回答的那一轮。
    expect(wrapper.findAll('article.customer').map((line) => line.get('.said').text())).toEqual([
      '第一句',
    ])

    stream.on(1).onStarted()
    await nextTick()

    // 轮到它了：这时才落进对话，排在第一轮后面。
    expect(wrapper.findAll('article.customer').map((line) => line.get('.said').text())).toEqual([
      '第一句',
      '第二句',
    ])
  })

  it('轮到它开始时撤下排队标记：started 一到，它就是一轮普通的回复', async () => {
    const stream = busyConversation()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')
    stream.on(1).onQueued({ itemId: 'q1', position: 1 })
    await nextTick()
    expect(wrapper.find('.queue').exists()).toBe(true)

    stream.on(1).onStarted()
    await nextTick()

    expect(wrapper.find('.queue').exists()).toBe(false)
  })

  it('删掉排队中的那条：DELETE 服务端那一项，本地气泡一起收回且不报错', async () => {
    const stream = busyConversation()
    cancelQueuedMock.mockResolvedValue(null)
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')
    stream.on(1).onQueued({ itemId: 'q1', position: 1 })
    await nextTick()

    await wrapper.get('.queue-item button').trigger('click')
    await flushPromises()

    expect(cancelQueuedMock).toHaveBeenCalledWith(7, 'q1')
    expect(wrapper.find('.queue').exists()).toBe(false)

    // 服务端随后以 dropped 收尾这条流：是客户自己的删除，不是失败，不该弹错误也不该留下这句话。
    stream.reject(1, new ApiError(QUEUE_DROPPED, '排队中的这一轮已被取消', 200))
    await flushPromises()

    expect(wrapper.emitted('error')).toBeUndefined()
    expect(wrapper.text()).not.toContain('第二句')
  })

  it('排满 5 条就把发送键关掉：再多一条 429 不该由客户去撞上', async () => {
    const stream = busyConversation()
    const wrapper = await mountView()
    for (let index = 1; index <= 6; index++) {
      await sendLine(wrapper, `要排队的第 ${index} 句`)
    }

    // 第 1 句在跑，第 2..6 句在排队 —— 正好排满一条会话的上限。
    for (let index = 1; index <= 5; index++) {
      stream.on(index).onQueued({ itemId: `q${index}`, position: index })
    }
    await nextTick()

    expect(wrapper.get('.queue-head').text()).toBe('排队中：5 条')
    expect(sendButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('第一问还在答时发了第二问：第一问的答案落回原位，不掉到第二问后面', async () => {
    const stream = busyConversation()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')

    stream.resolve(0, reply({ reply: '第一句的回答' }))
    await flushPromises()
    // 第二句还在排队，所以会话区里只有第一轮；轮到它开始时才补进对话。
    stream.on(1).onStarted()
    await nextTick()

    // 气泡按时间顺序排：第一问、它的答案、第二问（轮到它了，正在生成）。
    expect(wrapper.findAll('article').map((line) => line.get('.said').text())).toEqual([
      '第一句',
      '第一句的回答',
      '第二句',
      '',
    ])
  })

  it('排队中的那一轮回来时客户已经切走：回复不落进另一段对话', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单'), conversation(8, '另一段')])
    historyMock.mockResolvedValue([])
    const stream = busyConversation()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')

    const other = wrapper.findAll('.entry').find((entry) => entry.text().includes('另一段'))
    if (!other) throw new Error('找不到另一段对话的入口')
    await other.trigger('click')
    await flushPromises()

    stream.resolve(1, reply({ reply: '迟到很久的回复。' }))
    await flushPromises()

    expect(wrapper.text()).not.toContain('迟到很久的回复。')
  })
})

describe('停止运行中的一轮', () => {
  /** The callbacks the view hands a stream, plus the settlers to end it deliberately. */
  interface Handlers {
    onToken: (delta: string) => void
    onQueued: (turn: QueuedTurn) => void
    onStarted: () => void
    onRun: (runId: number) => void
    onCancelled: (reply: string) => void
  }

  function streaming() {
    const seen: Handlers[] = []
    const settlers: {
      resolve: (response: ChatResponse) => void
      reject: (error: unknown) => void
    }[] = []
    chatStreamMock.mockImplementation((_message, _id, handlers) => {
      seen.push(handlers as Handlers)
      return new Promise<ChatResponse>((resolve, reject) => {
        settlers.push({ resolve, reject })
      })
    })
    return {
      on: (index: number): Handlers => seen[index]!,
      reject: (index: number, error: unknown): void => settlers[index]!.reject(error),
    }
  }

  async function sendLine(wrapper: ReturnType<typeof mount>, text: string): Promise<void> {
    await wrapper.get('textarea').setValue(text)
    await sendButton(wrapper).trigger('click')
    await flushPromises()
  }

  beforeEach(() => {
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock.mockResolvedValue([])
  })

  it('Run 一开就有了停止入口：点它 POST 到那条 Run 的停止端点', async () => {
    stopRunMock.mockResolvedValue(null)
    const stream = streaming()
    const wrapper = await mountView()
    await sendLine(wrapper, '帮我下单 2 个 GADGET-02')

    // No Run yet, so nothing to stop.
    expect(wrapper.find('.thinking button').exists()).toBe(false)

    stream.on(0).onRun(42)
    await nextTick()
    await wrapper.get('.thinking button').trigger('click')
    await flushPromises()

    expect(stopRunMock).toHaveBeenCalledWith(7, 42)
  })

  it('停止以 cancelled 收尾：保留半截回复、不弹错误，停止入口随之消失', async () => {
    const stream = streaming()
    const wrapper = await mountView()
    await sendLine(wrapper, '帮我下单 2 个 GADGET-02')

    stream.on(0).onRun(42)
    stream.on(0).onToken('我先说半句')
    await nextTick()
    stream.on(0).onCancelled('我先说半句')
    stream.reject(0, new ApiError(RUN_CANCELLED, '本轮已停止', 200))
    await flushPromises()

    // The half reply the server persisted stays on screen; the stop was the point, so no toast.
    expect(wrapper.text()).toContain('我先说半句')
    expect(wrapper.emitted('error')).toBeUndefined()
    expect(wrapper.find('.thinking').exists()).toBe(false)
  })

  it('一个字都还没出就停：服务端什么也没存，占位气泡跟着收回', async () => {
    const stream = streaming()
    const wrapper = await mountView()
    await sendLine(wrapper, '帮我下单 2 个 GADGET-02')

    // 客户消息 + 还在等首字的助手占位。
    expect(wrapper.findAll('article')).toHaveLength(2)
    expect(wrapper.find('.said.pending').exists()).toBe(true)

    stream.on(0).onRun(42)
    stream.on(0).onCancelled('')
    stream.reject(0, new ApiError(RUN_CANCELLED, '本轮已停止', 200))
    await flushPromises()

    // 没有回复就没有气泡，更不能留一个永远等不到字的输入指示。
    expect(wrapper.find('article.assistant').exists()).toBe(false)
    expect(wrapper.emitted('error')).toBeUndefined()
    // 问题确实没被回答，于是回到那条唯一的出路：重发。
    expect(wrapper.get('.retry-note').text()).toBe('没有收到回复')
  })

  it('停止只掐正在跑的那一轮，不动排在后面的队', async () => {
    const stream = streaming()
    const wrapper = await mountView()
    await sendLine(wrapper, '第一句')
    await sendLine(wrapper, '第二句')
    stream.on(1).onQueued({ itemId: 'q1', position: 1 })
    stream.on(0).onRun(42)
    await nextTick()
    expect(wrapper.get('.queue-head').text()).toBe('排队中：1 条')

    stream.on(0).onCancelled('半截')
    stream.reject(0, new ApiError(RUN_CANCELLED, '本轮已停止', 200))
    await flushPromises()

    // Stopping the running turn is a different action from deleting a queued one.
    expect(wrapper.get('.queue-head').text()).toBe('排队中：1 条')
    expect(cancelQueuedMock).not.toHaveBeenCalled()
  })
})

describe('归档与恢复', () => {
  beforeEach(() => {
    listConversationsMock.mockResolvedValue([])
    historyMock.mockResolvedValue([])
  })

  it('归档的会话收在底部折叠分组里，默认不展开', async () => {
    listConversationsMock.mockResolvedValue([
      conversation(7, '在用的'),
      conversation(9, '压箱底的', 'IDLE', null, 'ARCHIVED'),
    ])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('在用的')
    expect(wrapper.get('.archived-toggle').text()).toContain('已归档 1')
    // Hidden until asked for, but never gone.
    expect(wrapper.text()).not.toContain('压箱底的')

    await wrapper.get('.archived-toggle').trigger('click')

    expect(wrapper.text()).toContain('压箱底的')
  })

  it('行溢出菜单里的「归档」把会话收进归档组', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '要归档的')])
    archiveConversationMock.mockResolvedValue(conversation(7, '要归档的', 'IDLE', null, 'ARCHIVED'))

    const wrapper = await mountView()
    await wrapper.get('.entry-more').trigger('click')
    await wrapper.get('.menu-item').trigger('click')
    await flushPromises()

    expect(archiveConversationMock).toHaveBeenCalledWith(7)
    expect(wrapper.get('.archived-toggle').text()).toContain('已归档 1')
    expect(wrapper.find('.menu-item').exists()).toBe(false)
  })

  it('归档被 409 拒绝时如实报错，会话留在原处', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '正忙的', 'RUNNING')])
    archiveConversationMock.mockRejectedValue(new ApiError('CONFLICT', '正在跑，不能归档', 409))

    const wrapper = await mountView()
    await wrapper.get('.entry-more').trigger('click')
    await wrapper.get('.menu-item').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('error')?.[0]).toEqual(['正在跑，不能归档'])
    expect(wrapper.find('.archived-toggle').exists()).toBe(false)
    expect(wrapper.text()).toContain('正忙的')
  })

  it('归档组里的「恢复」把会话放回主列表', async () => {
    listConversationsMock.mockResolvedValue([conversation(9, '压箱底的', 'IDLE', null, 'ARCHIVED')])
    restoreConversationMock.mockResolvedValue(conversation(9, '压箱底的'))

    const wrapper = await mountView()
    await wrapper.get('.archived-toggle').trigger('click')
    await wrapper.get('.entry-more').trigger('click')
    await wrapper.get('.menu-item').trigger('click')
    await flushPromises()

    expect(restoreConversationMock).toHaveBeenCalledWith(9)
    expect(wrapper.find('.archived-toggle').exists()).toBe(false)
    expect(wrapper.text()).toContain('压箱底的')
  })
})

describe('状态推送', () => {
  it('另一个会话的状态变化实时更新侧栏徽标，不必刷新', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '在看的'), conversation(8, '另一段')])
    historyMock.mockResolvedValue([])
    let stream!: SessionStreamHandlers
    eventsStreamMock.mockImplementation((handlers) => {
      stream = handlers
      return new Promise<void>(() => {})
    })

    const wrapper = await mountView()
    expect(wrapper.findAll('.entry-state')).toHaveLength(0)

    // Another tab's turn starts on conversation 8; the badge shows up here without a reload.
    stream.onState(conversation(8, '另一段', 'RUNNING', 42))
    await nextTick()

    expect(wrapper.findAll('.entry-state').map((badge) => badge.text())).toEqual(['生成中…'])
    // The conversation on screen was not disturbed.
    expect(wrapper.get('.entry.active').text()).toContain('在看的')
  })

  it('首帧 snapshot 一次性填满侧栏（含归档分组）', async () => {
    listConversationsMock.mockResolvedValue([])
    historyMock.mockResolvedValue([])
    let stream!: SessionStreamHandlers
    eventsStreamMock.mockImplementation((handlers) => {
      stream = handlers
      return new Promise<void>(() => {})
    })

    const wrapper = await mountView()
    stream.onSnapshot([
      conversation(1, '在用的'),
      conversation(2, '压箱底的', 'IDLE', null, 'ARCHIVED'),
    ])
    await nextTick()

    expect(wrapper.get('.docket .row .entry-title').text()).toBe('在用的')
    expect(wrapper.get('.archived-toggle').text()).toContain('已归档 1')
  })
})

describe('切回一个正在跑的会话', () => {
  it('重订阅拿到累积文本，之后继续跟着增量长', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '在跑的', 'RUNNING', 42)])
    historyMock.mockResolvedValue([
      { id: 1, role: 'CUSTOMER', content: '帮我下单 2 个 GADGET-02', createdAt: '', action: null },
    ])
    let stream!: RunStreamHandlers
    runStreamMock.mockImplementation((_conversationId, _runId, handlers) => {
      stream = handlers
      return new Promise<void>(() => {})
    })

    const wrapper = await mountView()

    expect(runStreamMock).toHaveBeenCalledWith(7, 42, expect.any(Object), expect.anything())
    stream.onSnapshot('我准备好')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好')

    stream.onToken('了一个下单操作。')
    await nextTick()
    expect(wrapper.text()).toContain('我准备好了一个下单操作。')
  })

  it('正在跑的会话给得出停止入口：用服务端给的 runId，即使这一轮不是本标签页发的', async () => {
    stopRunMock.mockResolvedValue(null)
    listConversationsMock.mockResolvedValue([conversation(7, '在跑的', 'RUNNING', 42)])
    historyMock.mockResolvedValue([])

    const wrapper = await mountView()

    await wrapper.get('.thinking button').trigger('click')
    await flushPromises()

    expect(stopRunMock).toHaveBeenCalledWith(7, 42)
  })

  it('安静的会话不做重订阅', async () => {
    listConversationsMock.mockResolvedValue([conversation(7, '安静的')])
    historyMock.mockResolvedValue([])

    await mountView()

    expect(runStreamMock).not.toHaveBeenCalled()
  })
})

describe('快速操作的守卫', () => {
  it('前一次打开还挂着时又点了另一段：迟到的历史不覆盖新会话', async () => {
    let resolveFirst!: (messages: Message[]) => void
    listConversationsMock.mockResolvedValue([conversation(7, '慢的'), conversation(8, '快的')])
    const slow: Message = {
      id: 1,
      role: 'CUSTOMER',
      content: '慢会话里的旧消息。',
      createdAt: '',
      action: null,
    }
    const quick: Message = {
      id: 2,
      role: 'ASSISTANT',
      content: '快会话里的消息。',
      createdAt: '',
      action: null,
    }
    historyMock
      .mockImplementationOnce(
        () =>
          new Promise<Message[]>((resolve) => {
            resolveFirst = resolve
          }),
      )
      .mockResolvedValueOnce([quick])

    const wrapper = await mountView() // 打开第一条（7），它的历史还挂着
    const quickRow = wrapper.findAll('.entry').find((entry) => entry.text().includes('快的'))
    if (!quickRow) throw new Error('找不到快的那条')
    await quickRow.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('快会话里的消息。')

    // 迟到的第 7 条历史现在才回来：它属于一个已经不在屏幕上的会话，必须被丢弃。
    resolveFirst([slow])
    await flushPromises()

    expect(wrapper.text()).toContain('快会话里的消息。')
    expect(wrapper.text()).not.toContain('慢会话里的旧消息。')
  })

  it('确认时发现卡已被服务端改过（410）：重读这条会话，卡片以服务端状态盖章', async () => {
    const stale = pendingCard()
    const question: Message = {
      id: 1,
      role: 'CUSTOMER',
      content: '帮我下单',
      createdAt: '',
      action: null,
    }
    const cardLine: Message = {
      id: 2,
      role: 'ASSISTANT',
      content: stale.summary,
      createdAt: '',
      action: stale,
    }
    listConversationsMock.mockResolvedValue([conversation(7, '下单')])
    historyMock
      .mockResolvedValueOnce([question, cardLine])
      .mockResolvedValueOnce([question, { ...cardLine, action: { ...stale, status: 'EXPIRED' } }])
    vi.mocked(api.confirm).mockRejectedValue(new ApiError('ACTION_EXPIRED', '确认单已过期', 410))

    const wrapper = await mountView()
    await wrapper.get('.slip button.authority').trigger('click')
    await flushPromises()

    expect(historyMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.slip-state').text()).toBe('已过期')
    expect(wrapper.emitted('error')).toBeUndefined()
  })

  /**
   * 「新对话」的 POST 与它的状态推送是同一次创建的两个出口：服务端在 201 之前就把这条会话的 `state` 帧推给了
   * store（它得先建会话才能回会话 id）。视图若把响应体再插一次，侧栏就会出现同一会话的两行。
   */
  it('状态帧抢在 POST 响应之前到达：侧栏仍只有一条新对话', async () => {
    listConversationsMock.mockResolvedValue([])
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
    useSessionStore().upsert(conversation(7, null)) // 推送先到
    resolveCreation(conversation(7, null)) // 响应体后到，同一个 id
    await flushPromises()

    expect(wrapper.findAll('.entry')).toHaveLength(1)
    expect(wrapper.get('.entry.active').text()).toContain('新对话')
  })
})
