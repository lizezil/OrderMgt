/** Wire types — the shapes the backend actually returns. */

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface OrderLine {
  sku: string
  itemName: string
  quantity: number
  unitPrice: number
  lineAmount: number
}

export interface Order {
  orderNo: string
  status: 'CREATED' | 'COMPLETED' | 'CANCELLED'
  totalAmount: number
  lines: OrderLine[]
  createdAt: string
  updatedAt: string
}

export interface CatalogItem {
  sku: string
  name: string
  unitPrice: number
}

/** Stored conversation lifecycle. */
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED'

/** Derived runtime state, computed server-side on every read (see ADR-0011). */
export type ConversationState = 'IDLE' | 'RUNNING' | 'WAITING_APPROVAL'

export interface Conversation {
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
  lastMessageAt: string | null
  status: ConversationStatus
  state: ConversationState
  /** The Run under way when `state` is `RUNNING`; the handle a stop or a reattach needs (else null). */
  runId: number | null
}

export interface ActionField {
  label: string
  value: string
}

/** A mutating operation waiting for the customer to confirm. */
export interface ActionCard {
  token: string
  toolName: string
  title: string
  summary: string
  argumentsJson: string
  fields: ActionField[]
  confirmEndpoint: string
  rejectEndpoint: string
  expiresAt: string
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'EXPIRED'
}

export interface Message {
  id: number
  role: 'CUSTOMER' | 'ASSISTANT' | 'TOOL'
  content: string | null
  createdAt: string
  action: ActionCard | null
}

export interface ExecutedTool {
  name: string
  resultJson: string
}

/**
 * A streaming turn that had to wait its turn on a busy conversation, as reported by the `queued`
 * frame on that turn's own stream (ADR-0012). `itemId` is a runtime handle with no row behind it --
 * it is what `DELETE .../queue/{itemId}` needs, and it dies with the process.
 */
export interface QueuedTurn {
  itemId: string
  position: number
}

export interface ChatResponse {
  conversationId: number
  reply: string
  executed: ExecutedTool[]
  actions: ActionCard[]
  promptTokens: number
  completionTokens: number
  model: string | null
  latencyMs: number
}

export interface ActionResolution {
  token: string
  status: string
  resultJson: string | null
  replayed: boolean
}
