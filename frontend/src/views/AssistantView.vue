<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { api } from '../api'
import { errorMessage } from '../errors'
import type { ActionCard, ChatResponse, Conversation, Message } from '../types'
import ActionCardPanel from '../components/ActionCardPanel.vue'

const emit = defineEmits<{ (e: 'error', message: string): void }>()

const conversations = ref<Conversation[]>([])
const messages = ref<Message[]>([])
const activeId = ref<number | null>(null)
const draft = ref('')
const sending = ref(false)
const loading = ref(false)
const usage = ref<{ prompt: number; completion: number; latencyMs: number } | null>(null)
const scroller = ref<HTMLElement | null>(null)

let localId = -1

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

/** Tool output lines are machine text; everything else is prose. */
function isMachine(line: string): boolean {
  return /^\[[a-zA-Z]/.test(line.trim())
}

async function loadConversations(): Promise<void> {
  conversations.value = await api.listConversations()
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

async function newConversation(): Promise<void> {
  try {
    await createConversation()
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

/** How long reconcile waits between reads: the server may still be finishing the turn. */
const RECONCILE_DELAY_MS = 1500
const RECONCILE_ATTEMPTS = 3

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

async function send(): Promise<void> {
  const text = draft.value.trim()
  if (!text || sending.value) return

  draft.value = ''
  sending.value = true
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

    const response = await api.chatStream(text, conversationId, (delta) => {
      reply.content = (reply.content ?? '') + delta
      void scrollToEnd()
    })

    usage.value = {
      prompt: response.promptTokens,
      completion: response.completionTokens,
      latencyMs: response.latencyMs,
    }
    messages.value = messages.value.filter((message) => message !== reply)
    appendReply(response)
    // The first message is what titles the conversation, so the sidebar is now stale.
    await loadConversations()
  } catch (error) {
    // The half-rendered placeholder never survives a failure; what replaces it is not a guess but
    // the server's answer to "did this send actually land?" -- the stream dying says nothing
    // either way, because the backend finishes and persists the turn even with nobody listening.
    messages.value = messages.value.filter((message) => message !== placeholder)
    const outcome =
      conversationId === null ? 'not-sent' : await reconcile(conversationId, text, knownIds)
    if (outcome === 'not-sent') {
      messages.value = messages.value.filter((message) => message !== sent)
      draft.value = text
    }
    if (outcome !== 'recovered') fail(error)
  } finally {
    sending.value = false
    await scrollToEnd()
  }
}

function onResolved(message: Message, card: ActionCard): void {
  message.action = card
}

onMounted(async () => {
  try {
    await loadConversations()
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
          <span class="entry-title">{{ conversation.title || '新对话' }}</span>
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

        <article
          v-for="message in messages"
          :key="message.id"
          class="line"
          :class="message.role.toLowerCase()"
        >
          <p class="speaker">{{ message.role === 'CUSTOMER' ? '你' : '助手' }}</p>
          <div class="said">
            <template v-for="(line, index) in (message.content || '').split('\n')" :key="index">
              <code v-if="isMachine(line)" class="data">{{ line }}</code>
              <template v-else>{{ line }}<br /></template>
            </template>
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
        <div class="composer-row">
          <textarea
            v-model="draft"
            rows="1"
            placeholder="说点什么…（Enter 发送）"
            @keydown.enter.exact.prevent="send"
          />
          <button class="btn authority" :disabled="sending || !draft.trim()" @click="send">
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
