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

async function send(): Promise<void> {
  const text = draft.value.trim()
  if (!text || sending.value) return

  draft.value = ''
  sending.value = true
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
  await scrollToEnd()

  try {
    // The stream endpoint needs a conversation in its path, so start one when this is a fresh chat.
    let conversationId = activeId.value
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
    messages.value = messages.value.filter((message) => message !== sent && message !== placeholder)
    draft.value = text
    fail(error)
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
