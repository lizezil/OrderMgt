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

    expect(chatStreamMock).toHaveBeenCalledWith(
      '第二条的问题',
      2,
      expect.objectContaining({ onToken: expect.any(Function) }),
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
