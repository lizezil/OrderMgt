<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, ApiError, QUEUE_DROPPED } from '../api'
import {
  LOCAL_MESSAGE_ID_START,
  MAX_QUEUE_DEPTH,
  RECONCILE_ATTEMPTS,
  RECONCILE_DELAY_MS,
} from '../constants'
import { errorMessage } from '../errors'
import { renderMarkdown } from '../markdown'
import type { ActionCard, ChatResponse, Conversation, ConversationState, Message } from '../types'
import ActionCardPanel from '../components/ActionCardPanel.vue'

const emit = defineEmits<{ (e: 'error', message: string): void }>()

const conversations = ref<Conversation[]>([])
const messages = ref<Message[]>([])
const activeId = ref<number | null>(null)
const draft = ref('')
const loading = ref(false)
const usage = ref<{ prompt: number; completion: number; latencyMs: number } | null>(null)
const scroller = ref<HTMLElement | null>(null)

/** Sends still awaiting their stream. A count, not a lock: a busy conversation takes more of them. */
const inFlight = ref(0)

/**
 * Whether a turn is running or waiting right now. Drives the "助手正在拟…" note and hides the retry
 * entry -- while a turn is in flight the thread's tail is not settled enough to offer a re-send.
 */
const sending = computed(() => inFlight.value > 0)

let localId = LOCAL_MESSAGE_ID_START

/** One send waiting its turn on a busy conversation, keyed to the optimistic bubble it belongs to. */
interface QueuedSend {
  conversationId: number
  itemId: string
  messageId: number
  text: string
}

/** Every send currently waiting in line, across conversations; the strip shows the active one's. */
const queued = ref<QueuedSend[]>([])

/**
 * The line for the conversation on screen. The server owns the real line (another tab can add to
 * it), so this is only what this screen knows about -- enough to show the count and delete an item,
 * and the server's 429 remains the authority on capacity.
 */
const activeQueue = computed(() =>
  queued.value.filter((item) => item.conversationId === activeId.value),
)

const queueFull = computed(() => activeQueue.value.length >= MAX_QUEUE_DEPTH)

function fail(error: unknown): void {
  emit('error', errorMessage(error, '请求失败'))
}

async function scrollToEnd(): Promise<void> {
  await nextTick()
  const element = scroller.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

function formatWhen(iso: string | null): string {
  if (!iso) return ''
  const date = new Date(iso)
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  return sameDay
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

/**
 * The sidebar badge for a conversation's derived state. IDLE is the quiet default and shows
 * nothing -- a label on every row would only make the two that matter harder to spot. The server
 * derives the value; the screen just says it.
 */
function stateLabel(state: ConversationState): string {
  if (state === 'RUNNING') return '生成中…'
  if (state === 'WAITING_APPROVAL') return '待确认'
  return ''
}

/**
 * Where the thread ends on a question with no answer. That is the shape an interrupted Run leaves
 * behind -- its customer message was committed in `prepare`, its reply never arrived -- and a failed
 * or empty turn leaves the same shape; the customer sees the same thing either way ("没有收到回复"),
 * and the server deliberately keeps the reason out of the conversation state (ADR-0011). What
 * matters here is only that the question went unanswered.
 *
 * <p>A conversation whose Run is still going (state RUNNING) is excluded on purpose: there the reply
 * may merely be late, and resending would open a second Run for a turn that is still alive.
 */
const retryIndex = computed(() => {
  if (sending.value) return -1
  const last = messages.value.length - 1
  if (last < 0 || messages.value[last]!.role !== 'CUSTOMER') return -1
  const active = conversations.value.find((conversation) => conversation.id === activeId.value)
  if (active?.state === 'RUNNING') return -1
  return last
})

/**
 * Resends an unanswered question as a brand new turn: the interrupted Run is history, and the only
 * honest fix is another one -- a half-delivered reply is never resumed (ADR-0009). Going through
 * `send` keeps the retry on exactly the same path as any other send: the optimistic bubble, the
 * reconcile when the stream dies, the server-truth reconciliation.
 */
function retry(message: Message): void {
  if (sending.value) return
  draft.value = message.content ?? ''
  void send()
}

/** A queued marker is per-send: it goes away when the turn starts, fails, or is deleted. */
function forgetQueued(messageId: number): void {
  queued.value = queued.value.filter((item) => item.messageId !== messageId)
}

/** Whether a failed send was one the customer deleted from the line, rather than a real error. */
function isQueueDropped(error: unknown): boolean {
  return error instanceof ApiError && error.code === QUEUE_DROPPED
}

/**
 * Deletes one waiting item. A 404 means it was too late -- the turn reached the front and is
 * running -- which is the server telling the truth, so it is reported like any other failure and
 * the turn simply proceeds.
 */
async function cancelQueued(item: QueuedSend): Promise<void> {
  try {
    await api.cancelQueued(item.conversationId, item.itemId)
    forgetQueued(item.messageId)
  } catch (error) {
    fail(error)
  }
}

async function loadConversations(): Promise<void> {
  conversations.value = await api.listConversations()
}

/**
 * The first message titles its conversation, so the sidebar is stale after a send -- but only the
 * *sidebar*. Refreshing it must neither hold the composer busy (a quick second Enter used to be
 * silently dropped while the last one awaited this) nor fail a turn that already landed.
 */
function refreshConversations(): void {
  loadConversations().catch((error) => fail(error))
}

async function open(conversation: Conversation): Promise<void> {
  activeId.value = conversation.id
  usage.value = null
  loading.value = true
  try {
    messages.value = await api.history(conversation.id)
  } catch (error) {
    fail(error)
  } finally {
    loading.value = false
  }
  await scrollToEnd()
}

/** Starts a conversation, files it at the top of the rail and makes it active. */
async function createConversation(): Promise<Conversation> {
  const conversation = await api.startConversation()
  conversations.value = [conversation, ...conversations.value]
  activeId.value = conversation.id
  return conversation
}

/**
 * The switch the composer must not race: a 新对话 still being created, or the first load after
 * the view is rebuilt. Until it settles, `activeId` is the *previous* conversation -- a quick
 * Enter used to file the message there, silently merging two threads into one.
 */
let pendingSwitch: Promise<unknown> = Promise.resolve()

function track<T>(work: Promise<T>): Promise<T> {
  pendingSwitch = work
  return work
}

async function newConversation(): Promise<void> {
  try {
    await track(createConversation())
    messages.value = []
    usage.value = null
  } catch (error) {
    fail(error)
  }
}

function appendReply(response: ChatResponse): void {
  if (response.reply.trim()) {
    messages.value.push({
      id: localId--,
      role: 'ASSISTANT',
      content: response.reply,
      createdAt: new Date().toISOString(),
      action: null,
    })
  }
  // A card is its own turn, exactly as the backend persisted it.
  for (const action of response.actions) {
    messages.value.push({
      id: localId--,
      role: 'ASSISTANT',
      content: action.summary,
      createdAt: new Date().toISOString(),
      action,
    })
  }
}

type ReconcileOutcome = 'recovered' | 'landed' | 'not-sent' | 'unknown'

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * The last server message that is *this* send: the same words, under an id the screen did not
 * already hold before sending. The id check is what tells a re-send of identical text apart from
 * the older turn that happens to say the same thing -- without it, the old message's reply would
 * masquerade as recovery and swallow the new send silently.
 */
function lastCustomerIndex(history: Message[], text: string, knownIds: Set<number>): number {
  for (let index = history.length - 1; index >= 0; index--) {
    const message = history[index]!
    if (message.role === 'CUSTOMER' && message.content === text && !knownIds.has(message.id)) {
      return index
    }
  }
  return -1
}

/**
 * After a dead stream the server owns the truth: the customer message was committed before the
 * model was called, and -- delivery being decoupled from the turn on the backend -- the reply may
 * still land after we stopped listening. Refresh history until it settles into one of four
 * endings, so the customer sees what actually happened instead of an optimistic guess:
 *
 * - 'recovered' -- the reply arrived; server truth replaces everything, silently.
 * - 'landed'    -- the message is there but no reply came; keep the bubble and keep the draft
 *                  cleared, because resending would duplicate a message the server already has.
 * - 'not-sent'  -- the server never saw it; roll back the bubble, give the draft back.
 * - 'unknown'   -- history unreachable (or the customer opened another conversation meanwhile);
 *                  keep local state and let the caller report the original error.
 */
async function reconcile(
  conversationId: number,
  text: string,
  knownIds: Set<number>,
): Promise<ReconcileOutcome> {
  // Another send still in flight makes a wholesale reassignment wrong: its optimistic bubble is not
  // in the server's history yet -- a queued turn has not even been committed -- so replacing the
  // thread would erase it. 'unknown' is exactly this answer: keep local state, report the error.
  if (inFlight.value > 1) return 'unknown'
  let absent = false
  for (let attempt = 0; attempt < RECONCILE_ATTEMPTS; attempt++) {
    let history: Message[]
    try {
      history = await api.history(conversationId)
    } catch {
      return 'unknown'
    }
    // Checking after the await: opening another conversation while we waited must not have this
    // one's history written over its messages.
    if (activeId.value !== conversationId) return 'unknown'
    messages.value = history
    const index = lastCustomerIndex(history, text, knownIds)
    if (index < 0) {
      // The message is committed before the model runs, but a disconnect can arrive while that
      // commit is still in flight: one absent reading proves nothing, two of them do.
      if (!absent && attempt + 1 < RECONCILE_ATTEMPTS) {
        absent = true
        await delay(RECONCILE_DELAY_MS)
        continue
      }
      return 'not-sent'
    }
    absent = false
    if (history.length > index + 1) return 'recovered'
    if (attempt + 1 < RECONCILE_ATTEMPTS) await delay(RECONCILE_DELAY_MS)
  }
  return 'landed'
}

/**
 * Sends one message. Not gated on a turn already running: on a busy conversation the server puts
 * this one in line and answers on this same stream with a `queued` frame, so a second Enter while
 * the assistant is still answering is a normal thing to do, not a mistake to swallow.
 */
async function send(): Promise<void> {
  const text = draft.value.trim()
  if (!text) return

  // The message belongs to the conversation the in-flight switch settles on, never to the one
  // that happened to be active a moment ago. A failed switch already reported itself; the
  // composer then speaks for whatever is active, as before.
  await pendingSwitch.catch(() => {})

  draft.value = ''
  inFlight.value++
  // Identity for the reconcile below: ids the screen already held before this send, so re-sending
  // identical words can be told apart from the older turn that happens to say the same thing.
  const knownIds = new Set(messages.value.map((message) => message.id))
  messages.value.push({
    id: localId--,
    role: 'CUSTOMER',
    content: text,
    createdAt: new Date().toISOString(),
    action: null,
  })
  // The array holds the object we pushed, but the template renders Vue's reactive proxy of it;
  // only mutating *that* reference repaints, and it is the identity the cleanup below matches.
  const sent = messages.value[messages.value.length - 1]!
  let placeholder: Message | null = null
  // Hoisted so the catch can reconcile the conversation this message went to, even if the customer
  // opened another one while the stream was dying.
  let conversationId = activeId.value
  await scrollToEnd()

  try {
    // The stream endpoint needs a conversation in its path, so start one when this is a fresh chat.
    if (conversationId === null) {
      conversationId = (await createConversation()).id
    }

    // The reply is rendered as it arrives; the placeholder is swapped for the persisted turn once
    // the stream's `done` event lands.
    messages.value.push({
      id: localId--,
      role: 'ASSISTANT',
      content: '',
      createdAt: new Date().toISOString(),
      action: null,
    })
    const reply = messages.value[messages.value.length - 1]!
    placeholder = reply
    const target = conversationId

    const response = await api.chatStream(text, target, {
      onToken: (delta) => {
        reply.content = (reply.content ?? '') + delta
        void scrollToEnd()
      },
      onQueued: (turn) => {
        queued.value = [
          ...queued.value,
          { conversationId: target, itemId: turn.itemId, messageId: sent.id, text },
        ]
      },
      // The turn is out of the line now; the server says so before its first token, so the count
      // never keeps calling a running turn "waiting".
      onStarted: () => forgetQueued(sent.id),
    })

    forgetQueued(sent.id)
    // The turn belongs to the conversation it was asked in. A queued turn can land minutes after
    // the customer has moved on, so only a still-open thread gets its reply and its meter.
    if (activeId.value === target) {
      usage.value = {
        prompt: response.promptTokens,
        completion: response.completionTokens,
        latencyMs: response.latencyMs,
      }
      messages.value = messages.value.filter((message) => message !== reply)
      appendReply(response)
    }
    refreshConversations()
  } catch (error) {
    // The half-rendered placeholder never survives a failure; what replaces it is not a guess but
    // the server's answer to "did this send actually land?" -- the stream dying says nothing
    // either way, because the backend finishes and persists the turn even with nobody listening.
    forgetQueued(sent.id)
    messages.value = messages.value.filter((message) => message !== placeholder)
    if (isQueueDropped(error)) {
      // The customer deleted it from the line, so nothing was ever asked. Retract the bubble and
      // keep the draft cleared -- cancelling a queued send is not a reason to rewrite it.
      messages.value = messages.value.filter((message) => message !== sent)
      return
    }
    const outcome =
      conversationId === null ? 'not-sent' : await reconcile(conversationId, text, knownIds)
    if (outcome === 'not-sent') {
      messages.value = messages.value.filter((message) => message !== sent)
      draft.value = text
    }
    if (outcome !== 'recovered') fail(error)
  } finally {
    inFlight.value--
    await scrollToEnd()
  }
}

function onResolved(message: Message, card: ActionCard): void {
  message.action = card
}

onMounted(async () => {
  try {
    await track(loadConversations())
    if (conversations.value.length) {
      await open(conversations.value[0])
    }
  } catch (error) {
    fail(error)
  }
})
</script>

<template>
  <aside class="rail">
    <div class="rail-head">
      <button class="btn authority" @click="newConversation">＋ 新对话</button>
    </div>
    <ol class="docket">
      <li v-if="!conversations.length" class="docket-empty">
        还没有对话。新建一段，问问你的订单。
      </li>
      <li v-for="conversation in conversations" :key="conversation.id">
        <button
          class="entry"
          :class="{ active: conversation.id === activeId }"
          @click="open(conversation)"
        >
          <span class="entry-head">
            <span class="entry-title">{{ conversation.title || '新对话' }}</span>
            <span
              v-if="stateLabel(conversation.state)"
              class="entry-state"
              :class="conversation.state.toLowerCase()"
            >
              {{ stateLabel(conversation.state) }}
            </span>
          </span>
          <span class="entry-when data">{{ formatWhen(conversation.lastMessageAt) }}</span>
        </button>
      </li>
    </ol>
  </aside>

  <section class="sheet">
    <div ref="scroller" class="sheet-scroll">
      <div class="measure">
        <p v-if="!messages.length && !loading" class="blank">
          点「新对话」开始，或直接说一句：<br />
          <span class="data">帮我下单 2 个 GADGET-02</span>
        </p>

        <!-- Markdown, rendered by `renderMarkdown`: it escapes its input first, so the HTML below
             is only ever the tags that module wrote. -->
        <article
          v-for="(message, index) in messages"
          :key="message.id"
          class="line"
          :class="message.role.toLowerCase()"
        >
          <p class="speaker">{{ message.role === 'CUSTOMER' ? '你' : '助手' }}</p>
          <div class="said" v-html="renderMarkdown(message.content || '')" />
          <div v-if="index === retryIndex" class="retry">
            <span class="retry-note">没有收到回复</span>
            <button class="btn" @click="retry(message)">重试</button>
          </div>
          <ActionCardPanel
            v-if="message.action"
            :card="message.action"
            @resolved="(card) => onResolved(message, card)"
            @error="fail"
          />
        </article>

        <p v-if="sending" class="thinking">助手正在拟…</p>
      </div>
    </div>

    <div class="composer">
      <div class="measure">
        <!-- Turns waiting behind the one in flight on this conversation: visible, and deletable by id. -->
        <div v-if="activeQueue.length" class="queue">
          <span class="queue-head">排队中：{{ activeQueue.length }} 条</span>
          <span v-for="item in activeQueue" :key="item.itemId" class="queue-item">
            <span class="queue-text">{{ item.text }}</span>
            <button class="btn quiet" @click="cancelQueued(item)">删除</button>
          </span>
        </div>
        <div class="composer-row">
          <textarea
            v-model="draft"
            rows="1"
            placeholder="说点什么…（Enter 发送）"
            @keydown.enter.exact.prevent="send"
          />
          <button class="btn authority" :disabled="!draft.trim() || queueFull" @click="send">
            发送
          </button>
        </div>
        <div v-if="usage" class="meter">
          本轮 prompt {{ usage.prompt }} / completion {{ usage.completion }} tokens，模型耗时
          {{ usage.latencyMs }} ms，记忆窗口 {{ messages.length }} 条
        </div>
      </div>
    </div>
  </section>
</template>
