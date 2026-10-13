<script setup lang="ts">
import { ref } from 'vue'
import type { Conversation, ConversationState } from '../types'

/**
 * One conversation in the sidebar, with the overflow menu that archives or restores it. The menu is
 * the only place those actions live -- there is deliberately no hard delete (ADR-0013: archiving
 * hides, it never destroys).
 */
const props = defineProps<{ conversation: Conversation; active: boolean }>()
const emit = defineEmits<{
  (e: 'open', conversation: Conversation): void
  (e: 'archive', conversation: Conversation): void
  (e: 'restore', conversation: Conversation): void
}>()

const menuOpen = ref(false)

const archived = props.conversation.status === 'ARCHIVED'

function choose(action: 'archive' | 'restore'): void {
  menuOpen.value = false
  if (action === 'archive') emit('archive', props.conversation)
  else emit('restore', props.conversation)
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
 * The badge for a conversation's derived state. IDLE is the quiet default and shows nothing -- a
 * label on every row would only make the two that matter harder to spot. The server derives the
 * value; the row just says it.
 */
function stateLabel(state: ConversationState): string {
  if (state === 'RUNNING') return '生成中…'
  if (state === 'WAITING_APPROVAL') return '待确认'
  return ''
}
</script>

<template>
  <li class="row">
    <button class="entry" :class="{ active }" @click="emit('open', conversation)">
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
    <div class="entry-tools">
      <button
        class="entry-more"
        :aria-expanded="menuOpen"
        aria-label="会话操作"
        @click.stop="menuOpen = !menuOpen"
      >
        ⋯
      </button>
      <div v-if="menuOpen" class="entry-menu">
        <button v-if="archived" class="menu-item" @click.stop="choose('restore')">恢复</button>
        <button v-else class="menu-item" @click.stop="choose('archive')">归档</button>
      </div>
    </div>
  </li>
</template>
