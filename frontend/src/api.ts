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

  confirm: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/confirm`, { method: 'POST' }),

  reject: (token: string) =>
    request<ActionResolution>(`/api/assistant/actions/${token}/reject`, { method: 'POST' }),

  listOrders: (page = 1, size = 20) =>
    request<PageResult<Order>>(`/api/orders?page=${page}&size=${size}`),

  getOrder: (orderNo: string) => request<Order>(`/api/orders/${orderNo}`),

  listCatalog: () => request<CatalogItem[]>('/api/catalog/items'),
}
