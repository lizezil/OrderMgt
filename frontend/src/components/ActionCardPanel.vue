<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ActionCard, ActionResolution } from '../types'
import { api } from '../api'
import { errorMessage } from '../errors'

const props = defineProps<{ card: ActionCard }>()
const emit = defineEmits<{
  (e: 'resolved', card: ActionCard): void
  (e: 'error', message: string): void
}>()

const busy = defineModel<boolean>('busy', { default: false })

/** Only a card resolved in this session gets the stamp animation; history stays still. */
const justResolved = ref(false)

const isPending = computed(() => props.card.status === 'PENDING')
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

const expiryLabel = computed(() => {
  if (!isPending.value) return ''
  const at = new Date(props.card.expiresAt)
  const minutes = Math.round((at.getTime() - Date.now()) / 60000)
  return minutes > 1 ? `${minutes} 分钟后过期` : '即将过期'
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
    emit('error', errorMessage(error, '操作失败'))
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
      <span v-else class="label">未再执行</span>
      <span class="rule" />
      <span class="clock data">{{ expiryLabel }}</span>
    </div>

    <span v-if="stampText" class="stamp" :class="{ refused, land: justResolved }">{{
      stampText
    }}</span>
  </figure>
</template>
