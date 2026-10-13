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

export interface Conversation {
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
  lastMessageAt: string | null
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
