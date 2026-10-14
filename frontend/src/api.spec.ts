import { ReadableStream } from 'node:stream/web'
import { TextEncoder } from 'node:util'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, chatStream, login, logout, me, onUnauthorized } from './api'
import type { ChatResponse, QueuedTurn } from './types'

/**
 * What this file pins is the SPA's half of two contracts the backend cannot enforce for it:
 * which session a request speaks for (cookie + CSRF, never a header the page can write), and how
 * a streamed reply is reassembled.
 */

function jsonResponse(body: unknown, status = 200, statusText = 'OK'): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText,
    json: async () => body,
  } as unknown as Response
}

function ok<T>(data: T): Response {
  return jsonResponse({ code: 'OK', message: 'ok', data })
}

function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
}

function streamResponse(chunks: string[], status = 200, statusText = 'OK'): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText,
    body: streamOf(chunks),
  } as unknown as Response
}

/** Exactly the frame shape the backend's SseEmitter writes: no separator space after the colons. */
function frame(name: string, data: string): string {
  return `event:${name}\ndata:${data}\n\n`
}

const DONE: ChatResponse = {
  conversationId: 7,
  reply: '好的，我准备取消订单 ORD1，请你确认。',
  executed: [],
  actions: [],
  promptTokens: 11,
  completionTokens: 3,
  model: 'offline-mock',
  latencyMs: 42,
}

describe('身份：会话 cookie + CSRF，不再有客户号请求头', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
    onUnauthorized(() => {})
  })

  it('任何请求都不再携带 X-Customer-Id —— 服务端只认会话', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok([]))
    vi.stubGlobal('fetch', fetchMock)

    await api.listOrders(2, 50)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/orders?page=2&size=50')
    expect(init.headers).not.toMatchObject({ 'X-Customer-Id': expect.anything() })
    expect((init.headers as Record<string, string>)['X-Customer-Id']).toBeUndefined()
  })

  it('写操作带上 XSRF-TOKEN cookie 的原值 —— 服务端要求它与 cookie 一致', async () => {
    document.cookie = 'XSRF-TOKEN=tok123'
    const fetchMock = vi.fn().mockResolvedValue(ok(null))
    vi.stubGlobal('fetch', fetchMock)

    await api.startConversation()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok123' })
  })

  it('GET 不带 CSRF 头 —— 它不是变更，服务端也不校验', async () => {
    document.cookie = 'XSRF-TOKEN=tok123'
    const fetchMock = vi.fn().mockResolvedValue(ok([]))
    vi.stubGlobal('fetch', fetchMock)

    await api.listOrders()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('登录把客户号和 PIN 打在 JSON 里，不进 URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ customerId: '1001' }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(login('1001', '1111')).resolves.toBe('1001')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/auth/login')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ customerId: '1001', pin: '1111' }))
  })

  it('me() 在未登录时返回 null 而不是抛错 —— 首屏检查不是错误路径', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ code: 'UNAUTHORIZED', message: 'please sign in', data: null }, 401),
        ),
    )

    await expect(me()).resolves.toBeNull()
  })

  it('登出是 POST 到 /api/auth/logout', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(null))
    vi.stubGlobal('fetch', fetchMock)

    await logout()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/auth/logout')
    expect(init.method).toBe('POST')
  })

  it('业务请求的 401 触发掉登录处理 —— 会话过期直接回登录页', async () => {
    let unauthorized = 0
    onUnauthorized(() => {
      unauthorized += 1
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ code: 'UNAUTHORIZED', message: 'please sign in', data: null }, 401),
        ),
    )

    await expect(api.listOrders()).rejects.toMatchObject({ status: 401 })

    expect(unauthorized).toBe(1)
  })

  it('登录失败的 401 不触发掉登录处理 —— 那是 PIN 错，不是会话过期', async () => {
    let unauthorized = 0
    onUnauthorized(() => {
      unauthorized += 1
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { code: 'UNAUTHORIZED', message: 'customer id or PIN is incorrect', data: null },
            401,
          ),
        ),
    )

    await expect(login('1001', '0000')).rejects.toMatchObject({ status: 401 })

    expect(unauthorized).toBe(0)
  })
})

describe('统一响应体', () => {
  it('code 不是 OK 时抛出带 code / message / status 的 ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { code: 'FORBIDDEN', message: '无权访问该订单', data: null },
            403,
            'Forbidden',
          ),
        ),
    )

    await expect(api.getOrder('ORD1')).rejects.toMatchObject({
      code: 'FORBIDDEN',
      message: '无权访问该订单',
      status: 403,
    })
  })

  it('响应体不是 JSON 时退化成 HTTP_<status>，而不是把解析错误透出去', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        json: async () => {
          throw new SyntaxError('Unexpected token <')
        },
      } as unknown as Response),
    )

    await expect(api.listCatalog()).rejects.toMatchObject({ code: 'HTTP_502', status: 502 })
  })

  it('新对话不带 conversationId，继续对话带上', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ reply: '嗯' }))
    vi.stubGlobal('fetch', fetchMock)

    await api.chat('你好', null)
    await api.chat('你好', 7)

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/assistant/chat')
    expect(fetchMock.mock.calls[1]![0]).toBe('/api/assistant/chat?conversationId=7')
  })
})

describe('流式回复的客户端契约', () => {
  it('token 增量按序回调，done 事件给出完整回复（卡片整轮一起到）', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            frame('token', '我'),
            frame('token', '准备好了'),
            frame('done', JSON.stringify(DONE)),
          ]),
        ),
    )
    const deltas: string[] = []

    const result = await chatStream('帮我下单', 7, { onToken: (delta) => deltas.push(delta) })

    expect(deltas).toEqual(['我', '准备好了'])
    expect(result).toEqual(DONE)
  })

  it('中止信号随流式请求一起交给 fetch，中止后调用以中止错误收场', async () => {
    const fetchMock = vi.fn(
      (_url: string, init: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('The operation was aborted.', 'AbortError')),
          )
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const controller = new AbortController()
    const pending = chatStream('你好', 7, { onToken: () => {} }, controller.signal)

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.signal).toBeInstanceOf(AbortSignal)

    controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('token 自带的行首空格是正文的一部分 —— Spring 的 SseEmitter 不写分隔空格', async () => {
    // 真实线上字节（tcpdump 过）：`event:` 与 `data:` 后面没有 SSE 规范建议的那个分隔空格，
    // 所以 `data: I can help` 里的行首空格属于正文；按规范剥一个就会把词粘在一起（"I canhelp"）。
    const body =
      'event:token\ndata:Hello!\n\n' +
      'event:token\ndata: I can help\n\n' +
      `event:done\ndata:${JSON.stringify(DONE)}\n\n`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([body])))
    const deltas: string[] = []

    await chatStream('x', 7, { onToken: (delta) => deltas.push(delta) })

    expect(deltas).toEqual(['Hello!', ' I can help'])
  })

  it('一帧被读到一半时先缓冲，拼齐了才消费', async () => {
    const whole = frame('token', '你好') + frame('done', JSON.stringify(DONE))
    // Cut inside the event name: nothing is complete yet, so nothing may be emitted.
    const cut = 9
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(streamResponse([whole.slice(0, cut), whole.slice(cut)])),
    )
    const deltas: string[] = []

    const result = await chatStream('你好', 7, { onToken: (delta) => deltas.push(delta) })

    expect(deltas).toEqual(['你好'])
    expect(result.reply).toBe(DONE.reply)
  })

  it('兼容 CRLF 与多行 data（同一条消息的多行按换行拼接）', async () => {
    const body =
      'event:token\r\ndata:第一行\r\ndata:第二行\r\n\r\n' +
      `event:done\r\ndata:${JSON.stringify(DONE)}\r\n\r\n`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([body])))
    const deltas: string[] = []

    await chatStream('x', 7, { onToken: (delta) => deltas.push(delta) })

    expect(deltas).toEqual(['第一行\n第二行'])
  })

  it('error 事件让整次调用失败，并把上游的消息带出去', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(streamResponse([frame('error', '上游模型超时')])),
    )

    await expect(chatStream('x', 7, { onToken: () => {} })).rejects.toMatchObject({
      code: 'STREAM_ERROR',
      message: '上游模型超时',
    })
  })

  it('流结束仍没有 done 时报错，而不是把半截回复当成回复', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([frame('token', '嗯')])))

    await expect(chatStream('x', 7, { onToken: () => {} })).rejects.toMatchObject({
      code: 'STREAM_ERROR',
    })
  })

  it('流式请求声明接受 SSE，但不带任何身份头 —— 身份就是会话 cookie', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(streamResponse([frame('done', JSON.stringify(DONE))]))
    vi.stubGlobal('fetch', fetchMock)

    await chatStream('我有哪些订单？', 3, { onToken: () => {} })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(
      '/api/assistant/conversations/3/stream?message=' + encodeURIComponent('我有哪些订单？'),
    )
    expect(init.headers).toMatchObject({ Accept: 'text/event-stream' })
    expect((init.headers as Record<string, string>)['X-Customer-Id']).toBeUndefined()
  })

  it('会话不属于该客户时（403）直接失败，不进入解析', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([], 403, 'Forbidden')))

    await expect(chatStream('x', 9, { onToken: () => {} })).rejects.toMatchObject({
      code: 'HTTP_403',
      status: 403,
    })
  })

  it('queued 事件把「它在队伍里」和可删除的 id 一起报上来，回合照旧进行', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            frame('queued', '{"itemId":"q3","position":2}'),
            frame('token', '好了'),
            frame('done', JSON.stringify(DONE)),
          ]),
        ),
    )
    const seen: QueuedTurn[] = []

    const result = await chatStream('第二条', 7, {
      onToken: () => {},
      onQueued: (turn) => seen.push(turn),
    })

    expect(seen).toEqual([{ itemId: 'q3', position: 2 }])
    expect(result).toEqual(DONE)
  })

  it('started 事件表示它已经离开队伍、开始作答 —— 抢在第一个 token 之前', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            frame('queued', '{"itemId":"q1","position":1}'),
            frame('started', '{}'),
            frame('token', '好'),
            frame('done', JSON.stringify(DONE)),
          ]),
        ),
    )
    const events: string[] = []

    await chatStream('第二条', 7, {
      onToken: () => {},
      onQueued: () => events.push('queued'),
      onStarted: () => events.push('started'),
    })

    expect(events).toEqual(['queued', 'started'])
  })

  it('dropped 是一次取舍而非失败：以 QUEUE_DROPPED 收尾，不再等一个永远不会来的回复', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([frame('dropped', '{}')])))

    await expect(chatStream('被删掉的那条', 7, { onToken: () => {} })).rejects.toMatchObject({
      code: 'QUEUE_DROPPED',
    })
  })

  it('删一条排队项就是把它从这条会话的队列 URL 上 DELETE 掉', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(null))
    vi.stubGlobal('fetch', fetchMock)

    await api.cancelQueued(7, 'q3')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/assistant/conversations/7/queue/q3')
    expect(init.method).toBe('DELETE')
  })

  it('run 事件报上这一轮打开的 Run —— 停止按钮要的就是这个 id', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([frame('run', '{"runId":42}'), frame('done', JSON.stringify(DONE))]),
        ),
    )
    const seen: number[] = []

    await chatStream('你好', 7, { onToken: () => {}, onRun: (runId) => seen.push(runId) })

    expect(seen).toEqual([42])
  })

  it('cancelled 是停止的结果而非失败：报上半截回复，并以 RUN_CANCELLED 收尾', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([frame('token', '我先说'), frame('cancelled', '{"reply":"我先说"}')]),
        ),
    )
    const halves: string[] = []

    await expect(
      chatStream('你好', 7, { onToken: () => {}, onCancelled: (reply) => halves.push(reply) }),
    ).rejects.toMatchObject({ code: 'RUN_CANCELLED' })

    expect(halves).toEqual(['我先说'])
  })

  it('重订阅先收累积文本快照，再收 token 增量，最后 closed 收尾', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          streamResponse([
            frame('snapshot', '{"text":"前半句"}'),
            frame('token', '后半句'),
            frame('closed', '{"runId":42,"status":"COMPLETED"}'),
          ]),
        ),
    )
    const snapshots: string[] = []
    const tokens: string[] = []
    const closed: string[] = []

    await api.runStream(7, 42, {
      onSnapshot: (text) => snapshots.push(text),
      onToken: (delta) => tokens.push(delta),
      onClosed: (status) => closed.push(status),
    })

    expect(snapshots).toEqual(['前半句'])
    expect(tokens).toEqual(['后半句'])
    expect(closed).toEqual(['COMPLETED'])
  })

  it('重订阅打的是这条会话那条 Run 的事件端点', async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await api.runStream(7, 42, { onSnapshot: () => {}, onToken: () => {}, onClosed: () => {} })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/assistant/conversations/7/runs/42/events')
    expect((init.headers as Record<string, string>).Accept).toBe('text/event-stream')
  })

  it('停一个 Run 是 POST 到这条会话的 run 停止端点', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(null))
    vi.stubGlobal('fetch', fetchMock)

    await api.stopRun(7, 42)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/assistant/conversations/7/runs/42/stop')
    expect(init.method).toBe('POST')
  })
})
