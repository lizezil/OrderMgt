import { ReadableStream } from 'node:stream/web'
import { TextEncoder } from 'node:util'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, chatStream, getCustomerId, setCustomerId } from './api'
import type { ChatResponse } from './types'

/**
 * What this file pins is the SPA's half of two contracts the backend cannot enforce for it:
 * which customer a request speaks for, and how a streamed reply is reassembled.
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

describe('身份：客户号就是请求头', () => {
  beforeEach(() => localStorage.clear())

  it('没设置过时默认 1001，设置后按浏览器持久化', () => {
    expect(getCustomerId()).toBe('1001')

    setCustomerId('2002')

    expect(getCustomerId()).toBe('2002')
    expect(localStorage.getItem('ordermgt.customerId')).toBe('2002')
  })

  it('每个请求都带上 X-Customer-Id —— 服务端据此把查询收窄到本人', async () => {
    setCustomerId('4242')
    const fetchMock = vi.fn().mockResolvedValue(ok([]))
    vi.stubGlobal('fetch', fetchMock)

    await api.listOrders(2, 50)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/orders?page=2&size=50')
    expect(init.headers).toMatchObject({ 'X-Customer-Id': '4242' })
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
  beforeEach(() => localStorage.clear())

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

    const result = await chatStream('帮我下单', 7, (delta) => deltas.push(delta))

    expect(deltas).toEqual(['我', '准备好了'])
    expect(result).toEqual(DONE)
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

    await chatStream('x', 7, (delta) => deltas.push(delta))

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

    const result = await chatStream('你好', 7, (delta) => deltas.push(delta))

    expect(deltas).toEqual(['你好'])
    expect(result.reply).toBe(DONE.reply)
  })

  it('兼容 CRLF 与多行 data（同一条消息的多行按换行拼接）', async () => {
    const body =
      'event:token\r\ndata:第一行\r\ndata:第二行\r\n\r\n' +
      `event:done\r\ndata:${JSON.stringify(DONE)}\r\n\r\n`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([body])))
    const deltas: string[] = []

    await chatStream('x', 7, (delta) => deltas.push(delta))

    expect(deltas).toEqual(['第一行\n第二行'])
  })

  it('error 事件让整次调用失败，并把上游的消息带出去', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(streamResponse([frame('error', '上游模型超时')])),
    )

    await expect(chatStream('x', 7, () => {})).rejects.toMatchObject({
      code: 'STREAM_ERROR',
      message: '上游模型超时',
    })
  })

  it('流结束仍没有 done 时报错，而不是把半截回复当成回复', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([frame('token', '嗯')])))

    await expect(chatStream('x', 7, () => {})).rejects.toMatchObject({ code: 'STREAM_ERROR' })
  })

  it('流式请求也带客户头并声明接受 SSE —— 这正是用 fetch 而不是 EventSource 的原因', async () => {
    setCustomerId('9001')
    const fetchMock = vi
      .fn()
      .mockResolvedValue(streamResponse([frame('done', JSON.stringify(DONE))]))
    vi.stubGlobal('fetch', fetchMock)

    await chatStream('我有哪些订单？', 3, () => {})

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(
      '/api/assistant/conversations/3/stream?message=' + encodeURIComponent('我有哪些订单？'),
    )
    expect(init.headers).toMatchObject({
      'X-Customer-Id': '9001',
      Accept: 'text/event-stream',
    })
  })

  it('会话不属于该客户时（403）直接失败，不进入解析', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse([], 403, 'Forbidden')))

    await expect(chatStream('x', 9, () => {})).rejects.toMatchObject({
      code: 'HTTP_403',
      status: 403,
    })
  })
})
