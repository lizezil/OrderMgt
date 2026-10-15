<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ensureCsrf, logout as apiLogout, me, onUnauthorized } from './api'
import { useSessionStore } from './store'
import { useToast } from './toast'
import AssistantView from './views/AssistantView.vue'
import CatalogView from './views/CatalogView.vue'
import LoginView from './views/LoginView.vue'
import OrdersView from './views/OrdersView.vue'

type Tab = 'assistant' | 'orders' | 'catalog'

const tab = ref<Tab>('assistant')
/** The signed-in customer id (from the session), or null while signed out / still checking. */
const customer = ref<string | null>(null)
const booted = ref(false)

// The session is the identity: a 401 anywhere (expired session, signed out in another tab)
// drops straight back to the login page, and signing in as someone else rebuilds every view.
onMounted(async () => {
  onUnauthorized(() => {
    customer.value = null
  })
  try {
    await ensureCsrf()
    customer.value = await me()
  } finally {
    booted.value = true
  }
})

const viewerKey = computed(() => customer.value ?? '')

// The session store is a module singleton, and the conversation list, the open conversation and
// the queue inside it all belong to *this* customer. Signing out -- or signing in as somebody
// else -- must wipe it, or the next AssistantView mounts over the previous identity's rail.
watch(customer, () => useSessionStore().reset())

function signedIn(id: string): void {
  customer.value = id
}

async function signOut(): Promise<void> {
  try {
    await apiLogout()
  } finally {
    // Even a failed logout call ends this tab's session locally; the server-side session is
    // already gone in the normal case, and a 401 on the next request would do the same thing.
    customer.value = null
  }
}

const { current: toastText, isError: toastIsError, push: pushToast } = useToast()
</script>

<template>
  <div class="shell">
    <LoginView v-if="booted && !customer" @login="signedIn" />

    <template v-else-if="customer">
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
        <span class="identity-name">客户 {{ customer }}</span>
        <button class="view" data-testid="logout" @click="signOut">登出</button>
      </header>

      <div class="stage">
        <AssistantView
          v-if="tab === 'assistant'"
          :key="viewerKey"
          :customer="customer"
          @error="pushToast"
        />
        <OrdersView v-else-if="tab === 'orders'" :key="viewerKey" />
        <CatalogView v-else />
      </div>
    </template>

    <div v-if="toastText" class="toast" :class="{ error: toastIsError }">{{ toastText }}</div>
  </div>
</template>
