<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, ApiError, QUEUE_DROPPED, RUN_CANCELLED } from '../api'
import {
  LOCAL_MESSAGE_ID_START,
  MAX_QUEUE_DEPTH,
  RECONCILE_ATTEMPTS,
  RECONCILE_DELAY_MS,
} from '../constants'
import { errorMessage } from '../errors'
import { renderMarkdown } from '../markdown'
import { useSessionStore } from '../store'
import type { QueuedTurnView } from '../store'
import type { ActionCard, ChatResponse, Conversation, Message } from '../types'
import ActionCardPanel from '../components/ActionCardPanel.vue'
import ConversationRow from '../components/ConversationRow.vue'

const emit = defineEmits<{ (e: 'error', message: string): void }>()

/**
 * The sidebar's conversation state lives in the shared store, not here: the status stream keeps it
 * fresh for every conversation (ADR-0014), and this view is one reader of it. `messages`, the draft
 * and the composer belong to the screen; the list, its buckets and the queue belong to the store.
 */
const store = useSessionStore()
const conversations = store.conversations
const archived = store.archived
/** Whether the archived group at the bottom of the rail is expanded. */
const showArchived = ref(false)
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

/**
 * The line for the conversation on screen, from the store. The server owns the real line -- another
 * tab can add to it, and a `queue` event tells this one -- so a waiting item with no words here is
 * another tab's send; deleting still works, because the item id is the server's. The 429 remains the
 * authority on capacity.
 */
const activeQueue = computed(() => store.queueFor(activeId.value))

const queueFull = computed(() => activeQueue.value.length >= MAX_QUEUE_DEPTH)

/**
 * The Run the customer can stop right now. Two ways to know it: this screen sent the turn and got
 * its `run` frame, or the conversation on screen is RUNNING and its `runId` came from the server --
 * which is how the stop button is reachable on a turn this tab only *reattached* to, or that another
 * tab started.
 */
interface LiveRun {
  conversationId: number
  runId: number
}
const liveRun = ref<LiveRun | null>(null)

const activeConversation = computed(
  () =>
    conversations.value.find((conversation) => conversation.id === activeId.value) ??
    archived.value.find((conversation) => conversation.id === activeId.value),
)

/** A run we can name for the conversation on screen; null means nothing to stop. */
const stoppableRunId = computed<number | null>(() => {
  if (liveRun.value?.conversationId === activeId.value) {
    return liveRun.value.runId
  }
  const active = activeConversation.value
  return active?.state === 'RUNNING' ? active.runId : null
})

/** The stop button shows only for a run on the conversation on screen (a stop changes no queue). */
const canStop = computed(() => stoppableRunId.value !== null)

/**
 * Stops the running turn (ADR-0012). The server aborts the model stream and closes the turn's
 * stream with a `cancelled` frame, which is what actually retracts the placeholder; this call only
 * expresses the intent. A failure here (e.g. the run already finished) is reported like any other.
 */
async function stopRun(): Promise<void> {
  const runId = stoppableRunId.value
  const conversationId = activeId.value
  if (runId === null || conversationId === null) return
  try {
    await api.stopRun(conversationId, runId)
  } catch (error) {
    fail(error)
  }
}

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

/** Whether a failed send was one the customer deleted from the line, rather than a real error. */
function isQueueDropped(error: unknown): boolean {
  return error instanceof ApiError && error.code === QUEUE_DROPPED
}

/** Whether a send ended because the customer stopped it -- a decision, not a failure. */
function isRunCancelled(error: unknown): boolean {
  return error instanceof ApiError && error.code === RUN_CANCELLED
}

/**
 * Deletes one waiting item. A 404 means it was too late -- the turn reached the front and is
 * running -- which is the server telling the truth, so it is reported like any other failure and
 * the turn simply proceeds.
 */
async function cancelQueued(item: QueuedTurnView): Promise<void> {
  const conversationId = activeId.value
  if (conversationId === null) return
  try {
    await api.cancelQueued(conversationId, item.itemId)
    store.forgetQueued(conversationId, item.itemId)
  } catch (error) {
    fail(error)
  }
}

/**
 * Archives a conversation -- hides it, never deletes it. The server refuses a non-quiescent one
 * (a Run in flight or a live card) with a 409, reported like any other failure (ADR-0013).
 */
async function archive(conversation: Conversation): Promise<void> {
  try {
    store.upsert(await api.archiveConversation(conversation.id))
  } catch (error) {
    fail(error)
  }
}

async function restore(conversation: Conversation): Promise<void> {
  try {
    store.upsert(await api.restoreConversation(conversation.id))
  } catch (error) {
    fail(error)
  }
}

/**
 * The first message titles its conversation, so the sidebar is stale after a send -- but only the
 * *sidebar*. Refreshing it must neither hold the composer busy (a quick second Enter used to be
 * silently dropped while the last one awaited this) nor fail a turn that already landed.
 */
function refreshConversations(): void {
  store.refresh().catch((error) => fail(error))
}

async function open(conversation: Conversation): Promise<void> {
  // Whatever this view was watching, it is not watching it any more.
  stopWatchingRun()
  activeId.value = conversation.id
  usage.value = null
  const ticket = ++openTicket
  loading.value = true
  let history: Message[]
  try {
    history = await api.history(conversation.id)
  } catch (error) {
    // A superseded open's failure belongs to a screen that no longer exists; only the newest
    // click gets to report (and to flip the loading flag off -- see the finally below).
    if (ticket === openTicket) fail(error)
    return
  } finally {
    if (ticket === openTicket) loading.value = false
  }
  // The click that opened this one was itself superseded while its history was in flight: its
  // thread must not repaint the screen the newer click is showing.
  if (ticket !== openTicket) return
  messages.value = history
  await scrollToEnd()
  // Coming back to a conversation whose turn is still running: reattach and keep watching it, so
  // the customer sees the reply being written rather than an empty thread (ADR-0014).
  if (conversation.state === 'RUNNING' && conversation.runId) {
    watchRun(conversation.id, conversation.runId)
  }
}

/**
 * Monotonic ticket for {@link open()}: only the newest click may write the thread, the loading
 * flag and the reattach. A slow history read landing after a newer open is stale by definition.
 */
let openTicket = 0

/** The reattach in progress, if any; aborted when the view moves to another conversation. */
let watching: AbortController | null = null

function stopWatchingRun(): void {
  watching?.abort()
  watching = null
}

/**
 * Subscribes to a Run that is already writing, rendering it into a live bubble: the first frame is
 * the text so far, then the deltas. It is best-effort like every stream (ADR-0009) -- the server
 * persists the turn regardless, so a dropped reattach loses nothing but the live view, and the
 * bubble already holds whatever was rendered.
 */
function watchRun(conversationId: number, runId: number): void {
  const controller = new AbortController()
  watching = controller
  messages.value.push({
    id: localId--,
    role: 'ASSISTANT',
    content: '',
    createdAt: new Date().toISOString(),
    action: null,
  })
  const live = messages.value[messages.value.length - 1]!
  void api
    .runStream(
      conversationId,
      runId,
      {
        onSnapshot: (text) => {
          live.content = text
          void scrollToEnd()
        },
        onToken: (delta) => {
          live.content = (live.content ?? '') + delta
          void scrollToEnd()
        },
        // The Run settled. What is on screen already equals what was persisted, so the bubble
        // stays; the sidebar's state comes from the status stream.
        onClosed: () => stopWatchingRun(),
      },
      controller.signal,
    )
    .catch(() => {
      // A reattach that cannot connect is not an error to report: history still holds the truth,
      // and the thread is fully readable without the live view.
      if (messages.value.includes(live) && !(live.content ?? '').trim()) {
        messages.value = messages.value.filter((message) => message !== live)
      }
    })
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

/** Every stream this screen opened, so leaving the screen can tear them all down at once. */
const sendControllers = new Set<AbortController>()

/** Flipped on unmount: after it, failures need no reporting and no reconciliation. */
let unmounted = false

onUnmounted(() => {
  unmounted = true
  for (const controller of sendControllers) controller.abort()
  sendControllers.clear()
  stopWatchingRun()
})

/**
 * Sends one message. Not gated on a turn already running: on a busy conversation the server puts
 * this one in line and answers on this same stream with a `queued` frame, so a second Enter while
 * the assistant is still answering is a normal thing to do, not a mistake to swallow.
 */
async function send(): Promise<void> {
  const text = draft.value.trim()
  if (!text) return

  // The composer empties and goes busy before any await: a second Enter inside the switch's race
  // window then reads an empty draft instead of firing the same words twice.
  draft.value = ''
  inFlight.value++

  // The message belongs to the conversation the in-flight switch settles on, never to the one
  // that happened to be active a moment ago. A failed switch already reported itself; the
  // composer then speaks for whatever is active, as before.
  await pendingSwitch.catch(() => {})

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
  // Set by the stream: the queue item this send got, the run it opened, and the half reply a stop
  // leaves behind.
  let myItemId: string | null = null
  let myRunId: number | null = null
  let cancelledReply: string | null = null
  // The abort handle for this send: unmounting the screen aborts it, so no fetch outlives the
  // component that was reading it.
  const controller = new AbortController()
  sendControllers.add(controller)
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

    const response = await api.chatStream(
      text,
      target,
      {
        onToken: (delta) => {
          reply.content = (reply.content ?? '') + delta
          void scrollToEnd()
        },
        onQueued: (turn) => {
          // Remember the id so the turn's own frames (started / done / failed) can clear its marker.
          myItemId = turn.itemId
          store.noteQueued(target, turn.itemId, text)
        },
        // The turn is out of the line now; the server says so before its first token, so the count
        // never keeps calling a running turn "waiting".
        onStarted: () => {
          if (myItemId !== null) store.forgetQueued(target, myItemId)
        },
        // The run exists now: this is the handle the stop button targets.
        onRun: (runId) => {
          myRunId = runId
          liveRun.value = { conversationId: target, runId }
        },
        // The customer stopped it; keep the half reply the server persisted.
        onCancelled: (reply) => {
          cancelledReply = reply
        },
      },
      controller.signal,
    )

    if (myItemId !== null) store.forgetQueued(target, myItemId)
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
    if (conversationId !== null && myItemId !== null) {
      store.forgetQueued(conversationId, myItemId)
    }
    if (isRunCancelled(error)) {
      // The customer stopped it: the half reply is already in the placeholder (the tokens that
      // arrived) and is now an ordinary ASSISTANT message on the server. Keep the bubble; the stop
      // was the point, so there is nothing to report.
      if (placeholder && cancelledReply !== null) placeholder.content = cancelledReply
      return
    }
    messages.value = messages.value.filter((message) => message !== placeholder)
    if (isQueueDropped(error)) {
      // The customer deleted it from the line, so nothing was ever asked. Retract the bubble and
      // keep the draft cleared -- cancelling a queued send is not a reason to rewrite it.
      messages.value = messages.value.filter((message) => message !== sent)
      return
    }
    // Leaving this screen aborted the stream on purpose: a decision like the stop button's, not a
    // failure to reconcile or report. The server finishes and persists the turn regardless.
    if (unmounted) return
    const outcome =
      conversationId === null ? 'not-sent' : await reconcile(conversationId, text, knownIds)
    if (outcome === 'not-sent') {
      messages.value = messages.value.filter((message) => message !== sent)
      draft.value = text
    }
    if (outcome !== 'recovered') fail(error)
  } finally {
    sendControllers.delete(controller)
    inFlight.value--
    // This turn's run is over -- done, failed, stopped or dropped -- so there is nothing to stop.
    if (myRunId !== null && liveRun.value?.runId === myRunId) liveRun.value = null
    await scrollToEnd()
  }
}

function onResolved(message: Message, card: ActionCard): void {
  message.action = card
}

/**
 * A 409/410 on resolve means the server moved this card without us (it expired, or another tab
 * resolved it): the honest fix is to re-read the thread so the slip stamps the server's state,
 * not to keep offering a button the server will refuse forever.
 */
async function refreshFromServer(): Promise<void> {
  const conversationId = activeId.value
  if (conversationId === null) return
  try {
    const history = await api.history(conversationId)
    if (activeId.value === conversationId) messages.value = history
  } catch (error) {
    fail(error)
  }
}

onMounted(async () => {
  try {
    // Subscribe first, then read: the stream's snapshot and this REST read both fill the store, so
    // a conversation created after the read still arrives as a `state` increment.
    store.connect()
    await track(store.refresh())
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
      <li v-if="!conversations.length && !archived.length" class="docket-empty">
        还没有对话。新建一段，问问你的订单。
      </li>
      <ConversationRow
        v-for="conversation in conversations"
        :key="conversation.id"
        :conversation="conversation"
        :active="conversation.id === activeId"
        @open="open"
        @archive="archive"
        @restore="restore"
      />
      <!-- Archived conversations: hidden by default, but readable and restorable in one click.
           Archiving is the only lifecycle action -- there is no hard delete (ADR-0013). -->
      <template v-if="archived.length">
        <li class="archived-head">
          <button class="archived-toggle" @click="showArchived = !showArchived">
            已归档 {{ archived.length }}
            <span class="archived-caret">{{ showArchived ? '▾' : '▸' }}</span>
          </button>
        </li>
        <ConversationRow
          v-for="conversation in showArchived ? archived : []"
          :key="conversation.id"
          :conversation="conversation"
          :active="conversation.id === activeId"
          @open="open"
          @archive="archive"
          @restore="restore"
        />
      </template>
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
            @stale="refreshFromServer"
            @error="fail"
          />
        </article>

        <!-- Shown while this screen is sending, or while the conversation on screen has a live Run
             (e.g. one this tab only reattached to, or another tab started). -->
        <p v-if="sending || canStop" class="thinking">
          <span>助手正在拟…</span>
          <!-- Stopping is its own action: it aborts the running turn only, and never touches the
               queue behind it (that is the queue strip's 删除 above). -->
          <button v-if="canStop" class="btn quiet" @click="stopRun">停止</button>
        </p>
      </div>
    </div>

    <div class="composer">
      <div class="measure">
        <!-- Turns waiting behind the one in flight on this conversation: visible, and deletable by id. -->
        <div v-if="activeQueue.length" class="queue">
          <span class="queue-head">排队中：{{ activeQueue.length }} 条</span>
          <span v-for="item in activeQueue" :key="item.itemId" class="queue-item">
            <!-- Words are only ever known to the tab that typed them; another tab's item is still
                 shown (and deletable) by its id, just without them. -->
            <span class="queue-text">{{ item.text || '（另一处的发送）' }}</span>
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
