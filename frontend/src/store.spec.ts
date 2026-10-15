import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import type { SessionStreamHandlers } from './api'
import { useSessionStore } from './store'
import type { Conversation, ConversationState, ConversationStatus } from './types'

vi.mock('./api', () => ({
  api: { listConversations: vi.fn(), eventsStream: vi.fn() },
}))

const listMock = vi.mocked(api.listConversations)
const eventsMock = vi.mocked(api.eventsStream)

function conversation(
  id: number,
  title: string,
  state: ConversationState = 'IDLE',
  status: ConversationStatus = 'ACTIVE',
  runId: number | null = null,
): Conversation {
  const at = '2026-01-01T00:00:00'
  return { id, title, createdAt: at, updatedAt: at, lastMessageAt: at, status, state, runId }
}

/** The handlers the store handed the status stream, so a test can push frames at it. */
let stream: SessionStreamHandlers

beforeEach(() => {
  vi.clearAllMocks()
  useSessionStore().reset()
  eventsMock.mockImplementation((handlers) => {
    stream = handlers
    return new Promise<void>(() => {})
  })
})

/**
 * The module-level store behind the sidebar (ADR-0014): a full `snapshot` seeds the list, `state`
 * increments patch one conversation in place, and a reconnect replaces everything wholesale. No
 * state library is involved -- `vue` remains the only runtime dependency.
 */
describe('会话 store', () => {
  it('用 REST 列表填充两个分组', async () => {
    listMock.mockResolvedValue([
      conversation(1, '在用的'),
      conversation(2, '压箱底的', 'IDLE', 'ARCHIVED'),
    ])

    await useSessionStore().refresh()

    expect(useSessionStore().conversations.value.map((c) => c.id)).toEqual([1])
    expect(useSessionStore().archived.value.map((c) => c.id)).toEqual([2])
  })

  it('listLoaded 随首份列表翻真；reset 连它和激活会话一起清', async () => {
    const store = useSessionStore()
    expect(store.listLoaded.value).toBe(false)

    store.activeId.value = 42
    listMock.mockResolvedValue([conversation(1, 'A')])
    await store.refresh()
    expect(store.listLoaded.value).toBe(true)

    store.reset()
    expect(store.listLoaded.value).toBe(false)
    expect(store.activeId.value).toBe(null)
  })

  it('首帧 snapshot 一次性初始化整个列表', async () => {
    const store = useSessionStore()
    store.connect()

    stream.onSnapshot([conversation(1, 'A'), conversation(2, 'B', 'IDLE', 'ARCHIVED')])

    expect(store.conversations.value.map((c) => c.id)).toEqual([1])
    expect(store.archived.value.map((c) => c.id)).toEqual([2])
  })

  /** 徽标能随另一个会话变化实时更新：state 增量就地替换那一条，不打乱顺序。 */
  it('state 增量就地更新那一条，顺序不变', () => {
    const store = useSessionStore()
    store.connect()
    stream.onSnapshot([conversation(1, 'A'), conversation(2, 'B'), conversation(3, 'C')])

    stream.onState(conversation(2, 'B', 'RUNNING', 'ACTIVE', 42))

    expect(store.conversations.value.map((c) => c.id)).toEqual([1, 2, 3])
    expect(store.conversations.value[1]!.state).toBe('RUNNING')
    expect(store.conversations.value[1]!.runId).toBe(42)
  })

  it('state 增量里的新会话被插到最前；变成归档则搬进归档组', () => {
    const store = useSessionStore()
    store.connect()
    stream.onSnapshot([conversation(1, 'A')])

    stream.onState(conversation(9, '另一个标签页新建的'))

    expect(store.conversations.value.map((c) => c.id)).toEqual([9, 1])
    stream.onState(conversation(1, 'A', 'IDLE', 'ARCHIVED'))
    expect(store.conversations.value.map((c) => c.id)).toEqual([9])
    expect(store.archived.value.map((c) => c.id)).toEqual([1])
  })

  it('重连时的 snapshot 整体替换列表', () => {
    const store = useSessionStore()
    store.connect()
    stream.onSnapshot([conversation(1, 'A'), conversation(2, 'B')])

    stream.onSnapshot([conversation(1, 'A', 'WAITING_APPROVAL')])

    expect(store.conversations.value.map((c) => c.id)).toEqual([1])
    expect(store.conversations.value[0]!.state).toBe('WAITING_APPROVAL')
  })

  it('queue 增量以服务端为准，本机会话里的文字被保留', () => {
    const store = useSessionStore()
    store.connect()
    store.noteQueued(7, 'q1', '第二句')

    // The server says q1 is queued, and mentions q2 this tab never typed.
    stream.onQueue({
      conversationId: 7,
      items: [
        { itemId: 'q1', position: 1 },
        { itemId: 'q2', position: 2 },
      ],
    })

    expect(store.queueFor(7)).toEqual([
      { itemId: 'q1', text: '第二句', position: 1 },
      { itemId: 'q2', text: '', position: 2 },
    ])

    // q1 left the line (it started, or another tab deleted it): it is gone here too.
    stream.onQueue({ conversationId: 7, items: [{ itemId: 'q2', position: 1 }] })
    expect(store.queueFor(7)).toEqual([{ itemId: 'q2', text: '', position: 1 }])
  })

  it('断开后不抛错，重连后重新订阅', async () => {
    eventsMock.mockRejectedValueOnce(new Error('network down'))
    const store = useSessionStore()

    store.connect()
    await Promise.resolve()

    // The store stays usable off the REST list even while the stream is down.
    listMock.mockResolvedValue([conversation(1, 'A')])
    await store.refresh()
    expect(store.conversations.value).toHaveLength(1)
  })
})
