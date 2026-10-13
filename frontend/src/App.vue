<script setup lang="ts">
import { computed, ref } from 'vue'
import { getCustomerId, setCustomerId } from './api'
import { DEFAULT_CUSTOMER_ID } from './constants'
import { useToast } from './toast'
import AssistantView from './views/AssistantView.vue'
import CatalogView from './views/CatalogView.vue'
import OrdersView from './views/OrdersView.vue'

type Tab = 'assistant' | 'orders' | 'catalog'

const tab = ref<Tab>('assistant')
const customer = ref(getCustomerId())
const reload = ref(0)

// The customer id stands in for a login, so it belongs to the shell: changing it must
// rebuild every view, which is what the key below does.
const viewerKey = computed(() => `${customer.value}-${reload.value}`)

function applyCustomer(): void {
  const next = customer.value.trim() || DEFAULT_CUSTOMER_ID
  customer.value = next
  setCustomerId(next)
  reload.value += 1
}

const { current: toastText, isError: toastIsError, push: pushToast } = useToast()
</script>

<template>
  <div class="shell">
    <header class="masthead">
      <div class="wordmark">Order <span>Assistant</span></div>
      <nav class="views" aria-label="视图">
        <button class="view" :class="{ active: tab === 'assistant' }" @click="tab = 'assistant'">
          助手
        </button>
        <button class="view" :class="{ active: tab === 'orders' }" @click="tab = 'orders'">
          订单
        </button>
        <button class="view" :class="{ active: tab === 'catalog' }" @click="tab = 'catalog'">
          商品
        </button>
      </nav>
      <div class="spacer" />
      <label class="identity">
        当前客户
        <input
          v-model="customer"
          class="data"
          @change="applyCustomer"
          @keyup.enter="applyCustomer"
        />
      </label>
    </header>

    <div class="stage">
      <AssistantView v-if="tab === 'assistant'" :key="viewerKey" @error="pushToast" />
      <OrdersView v-else-if="tab === 'orders'" :key="viewerKey" />
      <CatalogView v-else />
    </div>

    <div v-if="toastText" class="toast" :class="{ error: toastIsError }">{{ toastText }}</div>
  </div>
</template>
