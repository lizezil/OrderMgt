import type {
  ActionResolution,
  CatalogItem,
  ChatResponse,
  Conversation,
  Message,
  Order,
  PageResult,
  QueuedTurn,
} from './types'
import { DEFAULT_CUSTOMER_ID, DEFAULT_PAGE_SIZE, FIRST_PAGE } from './constants'

/**
 * The customer is simulated by a header, so it is a per-browser setting rather than a
 * login. Switching it is how you demo that the assistant cannot see anyone else's orders.
 */
const CUSTOMER_KEY = 'ordermgt.customerId'

export function getCustomerId(): string {
  return localStorage.getItem(CUSTOMER_KEY) ?? DEFAULT_CUSTOMER_ID
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
 * A blank line ends an SSE frame. The line ending may be LF, CRLF or CR, so the boundary has to
 * accept all three -- splitting on "\n\n" alone silently never completes a frame that ends CRLF,
 * which is what a proxy in front of the app may well produce.
 */
const FRAME_END = /\r\n\r\n|\n\n|\r\r/

/** Error code for a queued turn the customer deleted before it ever ran (see `dropped` below). */
export const QUEUE_DROPPED = 'QUEUE_DROPPED'

/** Error code for a turn the customer stopped (see `cancelled` below). Not a failure. */
export const RUN_CANCELLED = 'RUN_CANCELLED'

/** What a streaming turn reports as it goes: prose deltas, and its place in line if it has one. */
export interface ChatStreamHandlers {
  onToken: (delta: string) => void
  /** Called once if the turn had to wait on a busy conversation (ADR-0012). */
  onQueued?: (queued: QueuedTurn) => void
  /** Called when a queued turn leaves the line and starts running -- before its first token. */
  onStarted?: () => void
  /** Called once the turn has opened its Run, with the run id a stop needs (ADR-0012). */
  onRun?: (runId: number) => void
  /** Called if the customer stopped the turn; carries the half reply the server persisted. */
  onCancelled?: (reply: string) => void
}

/**
 * Streaming send. The endpoint is a GET carrying the message in the query string, so the customer
 * header still rides on the request — which is why this reads the body with fetch and a
 * ReadableStream rather than EventSource: EventSource cannot set request headers.
 *
 * <p>The contract mirrors the JSON endpoint: `token` events carry prose deltas, then one `done`
 * event carries the same ChatResponse (cards included). Four frames exist only here, because only a
 * stream can be waited on and stopped: `queued` says the turn is behind another one and hands back
 * the id its DELETE needs, `started` says it now has the conversation to itself, `run` names the Run
 * it opened (what a stop targets), and `dropped` says the customer removed it from the line, so it
 * will never run. An `error` event fails the promise; a `dropped` one rejects with {@link
 * QUEUE_DROPPED}, and a `cancelled` one with {@link RUN_CANCELLED} -- both decisions rather than
 * failures, handled without a toast.
 */
export async function chatStream(
  message: string,
  conversationId: number,
  handlers: ChatStreamHandlers,
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
  let dropped = false
  let cancelled = false

  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // A frame may be split across reads, so only consume what is complete and keep the remainder
    // buffered -- including a trailing "\r" that might still turn out to be "\r\n".
    let boundary = FRAME_END.exec(buffer)
    while (boundary) {
      const frame = buffer.slice(0, boundary.index)
      buffer = buffer.slice(boundary.index + boundary[0].length)
      const event = parseSseFrame(frame)
      if (event?.name === 'token') handlers.onToken(event.data)
      else if (event?.name === 'queued') reportQueued(event.data, handlers)
      else if (event?.name === 'started') handlers.onStarted?.()
      else if (event?.name === 'run') reportRun(event.data, handlers)
      else if (event?.name === 'cancelled') {
        cancelled = true
        reportCancelled(event.data, handlers)
      } else if (event?.name === 'done') result = JSON.parse(event.data) as ChatResponse
      else if (event?.name === 'dropped') dropped = true
      else if (event?.name === 'error') failure = event.data
      boundary = FRAME_END.exec(buffer)
    }
  }

  if (cancelled) {
    // The customer stopped this turn: the half reply is on screen and on the server. A decision,
    // not a failure, so it is reported with the response's own status.
    throw new ApiError(RUN_CANCELLED, '本轮已停止', 200)
  }
  if (dropped) {
    // Not a failure: the customer deleted this turn from the line, so nothing was ever asked. The
    // transport was fine, which is why the status is the response's own.
    throw new ApiError(QUEUE_DROPPED, '排队中的这一轮已被取消', 200)
  }
  if (failure) throw new ApiError('STREAM_ERROR', failure, 502)
  if (!result) throw new ApiError('STREAM_ERROR', '流式响应未返回结果', 502)
  return result
}

/** Reports a `run` frame: the run id a stop targets, ignored if the frame carries none. */
function reportRun(data: string, handlers: ChatStreamHandlers): void {
  try {
    const frame = JSON.parse(data) as { runId?: number }
    if (typeof frame.runId === 'number') handlers.onRun?.(frame.runId)
  } catch {
    // A malformed frame must not take the turn down with it; the stop button simply stays hidden.
  }
}

/** Reports a `cancelled` frame: the half reply the server persisted as an ASSISTANT message. */
function reportCancelled(data: string, handlers: ChatStreamHandlers): void {
  try {
    const frame = JSON.parse(data) as { reply?: string }
    handlers.onCancelled?.(typeof frame.reply === 'string' ? frame.reply : '')
  } catch {
    handlers.onCancelled?.('')
  }
}

/** Reports a `queued` frame, ignoring one that carries no usable id (then it is only visible). */
function reportQueued(data: string, handlers: ChatStreamHandlers): void {
  try {
    const queued = JSON.parse(data) as Partial<QueuedTurn>
    if (typeof queued.itemId === 'string') {
      handlers.onQueued?.({ itemId: queued.itemId, position: queued.position ?? 1 })
    }
  } catch {
    // A malformed frame must not take the turn down with it; the line is simply not deletable.
  }
}

function parseSseFrame(frame: string): { name: string; data: string } | null {
  let name = 'message'
  const data: string[] = []
  for (const line of frame.split(/\r\n|\r|\n/)) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    // No space-stripping after the colon: the SSE spec's optional separator space does not exist
    // on this wire -- Spring's SseEmitter writes `data:` verbatim -- so a payload that begins
    // with a space would lose a real word ("I can" + " help" arriving as "I canhelp").
    else if (line.startsWith('data:')) data.push(line.slice(5))
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

  /** Takes one waiting turn off the conversation's line; it will then never run (ADR-0012). */
  cancelQueued: (conversationId: number, itemId: string) =>
    request<null>(
      `/api/assistant/conversations/${conversationId}/queue/${encodeURIComponent(itemId)}`,
      { method: 'DELETE' },
    ),

  /**
   * Stops a running turn: the server aborts the model stream, keeps the half reply, and closes the
   * stream with a `cancelled` frame. Idempotent -- stopping a finished Run is a no-op (ADR-0012).
   */
  stopRun: (conversationId: number, runId: number) =>
    request<null>(`/api/assistant/conversations/${conversationId}/runs/${runId}/stop`, {
      method: 'POST',
    }),

  confirm: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/confirm`, { method: 'POST' }),

  reject: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/reject`, { method: 'POST' }),

  listOrders: (page = FIRST_PAGE, size = DEFAULT_PAGE_SIZE) =>
    request<PageResult<Order>>(`/api/orders?page=${page}&size=${size}`),

  getOrder: (orderNo: string) => request<Order>(`/api/orders/${orderNo}`),

  listCatalog: () => request<CatalogItem[]>('/api/catalog/items'),
}
