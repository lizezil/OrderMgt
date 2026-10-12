<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { Order } from '../types'

const orders = ref<Order[]>([])
const page = ref(1)
const size = 20
const total = ref(0)
const selected = ref<Order | null>(null)
const error = ref('')

async function load(): Promise<void> {
  error.value = ''
  try {
    const result = await api.listOrders(page.value, size)
    orders.value = result.items
    total.value = result.total
    selected.value = null
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '加载失败'
  }
}

function pages(): number {
  return Math.max(1, Math.ceil(total.value / size))
}

function changePage(delta: number): void {
  page.value += delta
  void load()
}

/** A state is a stamped mark: executed reads green, cancelled reads red, open stays plain. */
function markClass(status: Order['status']): string {
  if (status === 'COMPLETED') return 'mark done'
  if (status === 'CANCELLED') return 'mark refused'
  return 'mark'
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div class="panel-inner">
      <h1>订单</h1>
      <p class="sub">
        这里只出现当前客户名下的订单。换一个客户号再回来，你会看到完全不同的账目——
        查询一律按请求头里的客户收窄。
      </p>

      <p v-if="error" class="empty">{{ error }}</p>
      <p v-else-if="!orders.length" class="empty">
        这个客户还没有订单。去「助手」说一句「帮我下单 2 个 GADGET-02」，确认后就会落在这里。
      </p>

      <template v-else>
        <table class="ledger">
          <thead>
            <tr>
              <th>订单号</th>
              <th>状态</th>
              <th>明细</th>
              <th style="text-align: right">金额</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="order in orders"
              :key="order.orderNo"
              class="clickable"
              @click="selected = selected?.orderNo === order.orderNo ? null : order"
            >
              <td class="order-no">{{ order.orderNo }}</td>
              <td>
                <span :class="markClass(order.status)">{{ order.status }}</span>
              </td>
              <td>{{ order.lines.length }} 行</td>
              <td class="num"><span class="yen">¥</span>{{ order.totalAmount.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>

        <div class="pager">
          <button class="btn" :disabled="page <= 1" @click="changePage(-1)">上一页</button>
          <span class="data">{{ page }} / {{ pages() }} · 共 {{ total }} 单</span>
          <button class="btn" :disabled="page >= pages()" @click="changePage(1)">下一页</button>
        </div>

        <section v-if="selected" class="docket-open">
          <h2>
            <span class="order-no">{{ selected.orderNo }}</span>
            <span :class="markClass(selected.status)">{{ selected.status }}</span>
          </h2>
          <ul class="sub-lines">
            <li v-for="line in selected.lines" :key="line.sku">
              <span class="order-no">{{ line.sku }}</span>
              <span>{{ line.itemName }}</span>
              <span class="qty">× {{ line.quantity }}</span>
              <span class="amt">
                <span class="yen">¥</span>{{ line.lineAmount.toFixed(2) }}
                <span class="data" style="color: var(--ink-faint)">
                  （{{ line.unitPrice.toFixed(2) }} × {{ line.quantity }}）
                </span>
              </span>
            </li>
          </ul>
          <p class="total">
            合计
            <span class="data"
              ><span class="yen">¥</span>{{ selected.totalAmount.toFixed(2) }}</span
            >
          </p>
        </section>
      </template>
    </div>
  </div>
</template>
