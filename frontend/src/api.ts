import type {
  ActionResolution,
  CatalogItem,
  ChatResponse,
  Conversation,
  Message,
  Order,
  PageResult,
} from './types'

/**
 * The customer is simulated by a header, so it is a per-browser setting rather than a
 * login. Switching it is how you demo that the assistant cannot see anyone else's orders.
 */
const CUSTOMER_KEY = 'ordermgt.customerId'

export function getCustomerId(): string {
  return localStorage.getItem(CUSTOMER_KEY) ?? '1001'
}

export function setCustomerId(id: string): void {
  localStorage.setItem(CUSTOMER_KEY, id)
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

interface Envelope<T> {
  code: string
  message: string
  data: T
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Customer-Id': getCustomerId(),
      ...(init.headers ?? {}),
    },
  })

  const envelope = (await response.json().catch(() => null)) as Envelope<T> | null
  if (!response.ok || !envelope || envelope.code !== 'OK') {
    throw new ApiError(
      envelope?.code ?? `HTTP_${response.status}`,
      envelope?.message ?? response.statusText ?? '请求失败',
      response.status,
    )
  }
  return envelope.data
}

/**
 * Streaming send. The endpoint is a GET carrying the message in the query string, so the customer
 * header still rides on the request — which is why this reads the body with fetch and a
 * ReadableStream rather than EventSource: EventSource cannot set request headers.
 *
 * <p>The contract mirrors the JSON endpoint: `token` events carry prose deltas, then one `done`
 * event carries the same ChatResponse (cards included). An `error` event fails the promise.
 */
export async function chatStream(
  message: string,
  conversationId: number,
  onToken: (delta: string) => void,
): Promise<ChatResponse> {
  const response = await fetch(
    `/api/assistant/conversations/${conversationId}/stream?message=${encodeURIComponent(message)}`,
    { headers: { 'X-Customer-Id': getCustomerId(), Accept: 'text/event-stream' } },
  )
  if (!response.ok || !response.body) {
    throw new ApiError(
      `HTTP_${response.status}`,
      response.statusText || '流式请求失败',
      response.status,
    )
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: ChatResponse | null = null
  let failure: string | null = null

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE frames are separated by a blank line; a frame may be split across reads, so only consume
    // what is complete and keep the remainder buffered.
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseSseFrame(frame)
      if (event?.name === 'token') onToken(event.data)
      else if (event?.name === 'done') result = JSON.parse(event.data) as ChatResponse
      else if (event?.name === 'error') failure = event.data
      boundary = buffer.indexOf('\n\n')
    }
  }

  if (failure) throw new ApiError('STREAM_ERROR', failure, 502)
  if (!result) throw new ApiError('STREAM_ERROR', '流式响应未返回结果', 502)
  return result
}

function parseSseFrame(frame: string): { name: string; data: string } | null {
  let name = 'message'
  const data: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  return data.length ? { name, data: data.join('\n') } : null
}

export const api = {
  listConversations: () => request<Conversation[]>('/api/assistant/conversations'),

  startConversation: () =>
    request<Conversation>('/api/assistant/conversations', { method: 'POST' }),

  history: (conversationId: number) =>
    request<Message[]>(`/api/assistant/conversations/${conversationId}/messages`),

  /** One call covers both "continue this thread" and "start a new one". */
  chat: (message: string, conversationId: number | null) =>
    request<ChatResponse>(
      `/api/assistant/chat${conversationId == null ? '' : `?conversationId=${conversationId}`}`,
      { method: 'POST', body: JSON.stringify({ message }) },
    ),

  chatStream,

  confirm: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/confirm`, { method: 'POST' }),

  reject: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/reject`, { method: 'POST' }),

  listOrders: (page = 1, size = 20) =>
    request<PageResult<Order>>(`/api/orders?page=${page}&size=${size}`),

  getOrder: (orderNo: string) => request<Order>(`/api/orders/${orderNo}`),

  listCatalog: () => request<CatalogItem[]>('/api/catalog/items'),
}
