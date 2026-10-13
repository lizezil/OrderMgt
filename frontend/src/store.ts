import { ref } from 'vue'
import { api } from './api'
import { RECONNECT_DELAY_MS } from './constants'
import type { Conversation, QueuedTurn } from './types'

/**
 * The one place the sidebar's conversation state lives (ADR-0014). A module-level singleton, not a
 * state library: `vue` stays the only runtime dependency, and any component -- the sidebar badge for
 * a conversation the user is *not* looking at included -- reads the same refs, so a state change
 * pushed from the server reaches every view without prop plumbing.
 *
 * <p>It holds three things: the active conversations, the archived ones, and the waiting line per
 * conversation. The server is the authority for all three: they are seeded by the REST list and by
 * the status stream's `snapshot`, and kept fresh by its `state` / `queue` increments. A reconnect
 * re-sends the snapshot, which replaces the whole list -- there is no per-event resume to miss.
 */

/** One waiting item as this screen knows it: the runtime id, and its words if this tab typed them. */
export interface QueuedTurnView {
  itemId: string
  text: string
  position: number
}

const conversations = ref<Conversation[]>([])
const archived = ref<Conversation[]>([])
/** The waiting line per conversation id; only ids this client has seen appear here. */
const queue = ref<Record<number, QueuedTurnView[]>>({})

let connected = false
let stopped = false

function isArchived(conversation: Conversation): boolean {
  return conversation.status === 'ARCHIVED'
}

/** Replaces both buckets from a full list (`refresh` and the stream's `snapshot` share this). */
function replaceAll(all: Conversation[]): void {
  conversations.value = all.filter((conversation) => !isArchived(conversation))
  archived.value = all.filter(isArchived)
}

/** Replaces one conversation in place, or inserts it at the front when it is new to this screen. */
function upsert(conversation: Conversation): void {
  const bucket = isArchived(conversation) ? archived : conversations
  const other = isArchived(conversation) ? conversations : archived
  // A conversation never belongs to both groups: moving it is a replace, not an insert.
  other.value = other.value.filter((row) => row.id !== conversation.id)
  const index = bucket.value.findIndex((row) => row.id === conversation.id)
  if (index >= 0) {
    bucket.value.splice(index, 1, conversation)
  } else {
    bucket.value = [conversation, ...bucket.value]
  }
}

function queueFor(conversationId: number | null): QueuedTurnView[] {
  if (conversationId === null) return []
  return queue.value[conversationId] ?? []
}

/** Records a turn this screen just queued, with its words, as reported by its own `queued` frame. */
function noteQueued(conversationId: number, itemId: string, text: string): void {
  const line = queueFor(conversationId).filter((item) => item.itemId !== itemId)
  queue.value = {
    ...queue.value,
    [conversationId]: [...line, { itemId, text, position: line.length + 1 }],
  }
}

/**
 * Reconciles a conversation's line with the server's `queue` event: the server owns which items
 * exist and in what order; the words are only ever known to the tab that typed them, so a known id
 * keeps its text and an unknown one is shown without any (another tab's send). An item that has left
 * the line is gone, which is how this tab learns its own item started or was deleted elsewhere.
 */
function reconcileQueue(conversationId: number, items: QueuedTurn[]): void {
  const known = new Map(queueFor(conversationId).map((item) => [item.itemId, item.text]))
  const line = items.map((item, index) => ({
    itemId: item.itemId,
    text: known.get(item.itemId) ?? '',
    position: item.position ?? index + 1,
  }))
  queue.value = { ...queue.value, [conversationId]: line }
}

function forgetQueued(conversationId: number, itemId: string): void {
  const line = queueFor(conversationId).filter((item) => item.itemId !== itemId)
  queue.value = { ...queue.value, [conversationId]: line }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * Keeps the status stream open, reconnecting after a drop: the server re-sends the snapshot on
 * every connection, so a reconnect heals any state missed while disconnected. A failure is quiet --
 * the sidebar still works off the REST list, and a stream that cannot connect must not throw toasts
 * at a customer who can keep chatting.
 */
async function pump(): Promise<void> {
  while (!stopped) {
    try {
      await api.eventsStream({
        onSnapshot: replaceAll,
        onState: upsert,
        onQueue: ({ conversationId, items }) => reconcileQueue(conversationId, items),
      })
    } catch {
      // Dropped (network, server restart, the 1h ceiling): fall through to the retry.
    }
    if (stopped) return
    await delay(RECONNECT_DELAY_MS)
  }
}

export function useSessionStore() {
  return {
    conversations,
    archived,
    /** Replaces both lists from a full read, after a REST action or the stream's `snapshot`. */
    replaceAll,
    /** Files one conversation under the group its lifecycle now says it belongs to. */
    upsert,
    refresh: async (): Promise<void> => {
      replaceAll(await api.listConversations(true))
    },
    /** Opens the status stream (once). Safe to call from every mount. */
    connect: (): void => {
      if (connected) return
      connected = true
      stopped = false
      void pump()
    },
    disconnect: (): void => {
      stopped = true
      connected = false
    },
    queueFor,
    noteQueued,
    reconcileQueue,
    forgetQueued,
    /** Clears everything, including the connection; for tests. */
    reset: (): void => {
      stopped = true
      connected = false
      conversations.value = []
      archived.value = []
      queue.value = {}
    },
  }
}
