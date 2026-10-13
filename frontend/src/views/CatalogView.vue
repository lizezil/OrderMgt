<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { errorMessage } from '../errors'
import type { CatalogItem } from '../types'

const items = ref<CatalogItem[]>([])
const error = ref('')

onMounted(async () => {
  try {
    items.value = await api.listCatalog()
  } catch (caught) {
    error.value = errorMessage(caught, '加载失败')
  }
})
</script>

<template>
  <div class="panel">
    <div class="panel-inner">
      <h1>商品目录</h1>
      <p class="sub">
        这是全站唯一的价格来源。助手下单时只能填 sku 和数量，从不填金额——
        金额由服务端在这里查到单价后算出来。模型没有指定价格的通道。
      </p>

      <p v-if="error" class="empty">{{ error }}</p>
      <table v-else class="ledger">
        <thead>
          <tr>
            <th>SKU</th>
            <th>名称</th>
            <th style="text-align: right">单价</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.sku">
            <td class="order-no">{{ item.sku }}</td>
            <td>{{ item.name }}</td>
            <td class="num"><span class="yen">¥</span>{{ item.unitPrice.toFixed(2) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
