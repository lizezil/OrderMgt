<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, login } from '../api'

const emit = defineEmits<{ login: [customerId: string] }>()

const customerId = ref('')
const pin = ref('')
const error = ref('')
const busy = ref(false)

async function submit(): Promise<void> {
  if (!customerId.value.trim() || !pin.value) {
    error.value = '请填写客户编号和 PIN'
    return
  }
  busy.value = true
  error.value = ''
  try {
    emit('login', await login(customerId.value.trim(), pin.value))
  } catch (e) {
    // 401 here means "wrong pair", not "session expired" -- say it in the demo's language.
    error.value =
      e instanceof ApiError && e.status === 401 ? '客户编号或 PIN 不正确' : '登录失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="login" @submit.prevent="submit">
    <h1 class="login-wordmark">Order <span>Assistant</span></h1>
    <p class="login-hint">输入客户编号和 PIN 登录</p>
    <label class="login-field">
      客户编号
      <input v-model="customerId" class="data" autocomplete="username" data-testid="customer" />
    </label>
    <label class="login-field">
      PIN
      <input
        v-model="pin"
        class="data"
        type="password"
        autocomplete="current-password"
        data-testid="pin"
      />
    </label>
    <p v-if="error" class="login-error" role="alert">{{ error }}</p>
    <button class="login-submit" type="submit" :disabled="busy" data-testid="submit">
      {{ busy ? '登录中…' : '登录' }}
    </button>
  </form>
</template>
