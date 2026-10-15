<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ActionCard, ActionResolution } from '../types'
import { api, ApiError, ACTION_EXPIRED } from '../api'
import { EXPIRY_SOON_MINUTES, EXPIRY_TICK_MS, MS_PER_MINUTE } from '../constants'
import { errorMessage } from '../errors'

const props = defineProps<{ card: ActionCard }>()
const emit = defineEmits<{
  (e: 'resolved', card: ActionCard): void
  (e: 'error', message: string): void
  /** The server already moved this card (410 expired / 409 resolved elsewhere): re-read it. */
  (e: 'stale'): void
  /** A fresh card for the same proposal now sits in history (US-1.12): re-read the thread. */
  (e: 'reinitiated'): void
}>()

const busy = defineModel<boolean>('busy', { default: false })

/** Only a card resolved in this session gets the stamp animation; history stays still. */
const justResolved = ref(false)

const isPending = computed(() => props.card.status === 'PENDING')
const isExpired = computed(() => props.card.status === 'EXPIRED')
const executed = computed(() => props.card.status === 'CONFIRMED')
const refused = computed(() => props.card.status === 'REJECTED' || props.card.status === 'EXPIRED')

const slipClass = computed(() => ({
  done: executed.value,
  refused: refused.value,
}))

/** One switch names the state; the stamp repeats that word only once the state is terminal. */
const stateLabel = computed(() => {
  switch (props.card.status) {
    case 'CONFIRMED':
      return '已执行'
    case 'REJECTED':
      return '已拒绝'
    case 'EXPIRED':
      return '已过期'
    default:
      return '待授权'
  }
})

const stampText = computed(() => (props.card.status === 'PENDING' ? '' : stateLabel.value))

/** The wall clock the countdown reads, ticked while the card is pending: a computed that only
 * depends on `expiresAt` would evaluate `Date.now()` once and freeze the label forever. */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

watch(
  isPending,
  (pending) => {
    if (pending && ticker === null) {
      ticker = setInterval(() => {
        now.value = Date.now()
      }, EXPIRY_TICK_MS)
    } else if (!pending && ticker !== null) {
      clearInterval(ticker)
      ticker = null
    }
  },
  { immediate: true },
)
onUnmounted(() => {
  if (ticker !== null) clearInterval(ticker)
})

const expiryLabel = computed(() => {
  if (!isPending.value) return ''
  const at = new Date(props.card.expiresAt)
  const minutes = Math.round((at.getTime() - now.value) / MS_PER_MINUTE)
  return minutes > EXPIRY_SOON_MINUTES ? `${minutes} 分钟后过期` : '即将过期'
})

async function resolve(kind: 'confirm' | 'reject'): Promise<void> {
  busy.value = true
  try {
    const resolution: ActionResolution =
      kind === 'confirm' ? await api.confirm(props.card.token) : await api.reject(props.card.token)
    // The card's own status moves, so the slip re-renders as authorised rather than
    // staying clickable -- the UI must not offer a second confirmation.
    justResolved.value = true
    emit('resolved', { ...props.card, status: resolution.status as ActionCard['status'] })
  } catch (error) {
    if (
      error instanceof ApiError &&
      (error.code === ACTION_EXPIRED || error.status === 409 || error.status === 410)
    ) {
      // The server already moved this card (expired, or resolved from another tab): ask the owner
      // to re-read the thread so the slip stamps the truth, instead of reporting a "failure" and
      // leaving a button the server will refuse forever.
      emit('stale')
      return
    }
    emit('error', errorMessage(error, '操作失败'))
  } finally {
    busy.value = false
  }
}

/**
 * Re-mints an expired card (US-1.12): the server re-proposes the same tool and arguments as a
 * brand-new card and appends it to history, so the thread re-read is what brings it on screen.
 * A 409 says the local "已过期" is out of date -- the card is live or resolved elsewhere -- which
 * is the same re-read, told apart from a real failure.
 */
async function reinitiate(): Promise<void> {
  busy.value = true
  try {
    await api.reinitiate(props.card.token)
    emit('reinitiated')
  } catch (error) {
    if (error instanceof ApiError && (error.status === 409 || error.status === 410)) {
      emit('stale')
      return
    }
    emit('error', errorMessage(error, '重新发起失败'))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <figure class="slip" :class="slipClass">
    <figcaption class="slip-head">
      <span class="slip-kind">{{ card.title }}</span>
      <span class="slip-state">{{ stateLabel }}</span>
    </figcaption>

    <dl v-if="card.fields.length" class="slip-fields">
      <div v-for="field in card.fields" :key="field.label" class="row">
        <dt>{{ field.label }}</dt>
        <dd>{{ field.value }}</dd>
      </div>
    </dl>

    <div class="slip-sign">
      <template v-if="isPending">
        <button class="btn authority" :disabled="busy" @click="resolve('confirm')">授权执行</button>
        <button class="btn quiet" :disabled="busy" @click="resolve('reject')">拒绝</button>
      </template>
      <!-- An expired card is the one dead state with a way forward: re-mint the same proposal
           (US-1.12). A rejected card deliberately gets no such door -- refusing was the customer's
           own word, and re-proposing is theirs to say again. -->
      <template v-else-if="isExpired">
        <button class="btn" :disabled="busy" @click="reinitiate">重新发起</button>
        <span class="label">未执行</span>
      </template>
      <!-- One line where the buttons were, worded for the state it is in: an executed card says
           the door is shut now (no second execution), a refused one says nothing ever went
           through. "未再执行" over both read as a contradiction on a card stamped 已执行. -->
      <span v-else-if="executed" class="label">不会再执行</span>
      <span v-else class="label">未执行</span>
      <span class="rule" />
      <span class="clock data">{{ expiryLabel }}</span>
    </div>

    <span v-if="stampText" class="stamp" :class="{ refused, land: justResolved }">{{
      stampText
    }}</span>
  </figure>
</template>
