<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { orderApi } from '@/api/order'
import { getStatusText, orderStatusText } from '@/constants/status'
import { formatDate, formatPrice, getErrorMessage } from '@/utils/format'
import type { OrderVO } from '@/types/order'

const props = defineProps<{ mode: 'buy' | 'sell' }>()
const router = useRouter()

const loading = ref(false)
const orders = ref<OrderVO[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const actingId = ref<number | null>(null)
const detailVisible = ref(false)
const detail = ref<OrderVO>()
const detailLoading = ref(false)

const isBuy = () => props.mode === 'buy'

async function loadOrders() {
  loading.value = true
  try {
    const page = isBuy() ? await orderApi.buy(pageNo.value, pageSize) : await orderApi.sell(pageNo.value, pageSize)
    orders.value = page.records
    total.value = page.total
  } catch (error) {
    orders.value = []
    ElMessage.error(getErrorMessage(error, '订单列表加载失败'))
  } finally {
    loading.value = false
  }
}

function changePage(next: number) { pageNo.value = next; void loadOrders() }

async function openDetail(order: OrderVO) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await orderApi.getById(order.id)
  } catch (error) {
    detailVisible.value = false
    ElMessage.error(getErrorMessage(error, '订单详情加载失败'))
  } finally {
    detailLoading.value = false
  }
}

async function confirmOrder(order: OrderVO) {
  try {
    await ElMessageBox.confirm(`确定确认订单「${order.productTitle}」吗？确认后订单完成，商品将标记为已售出。`, '确认订单', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' })
  } catch {
    return // 用户取消
  }
  actingId.value = order.id
  try {
    await orderApi.confirm(order.id)
    ElMessage.success('订单已确认')
    await loadOrders()
    if (detail.value?.id === order.id) detail.value = undefined // 详情已过期，关闭后重新查看
    detailVisible.value = false
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '确认失败'))
  } finally {
    actingId.value = null
  }
}

async function cancelOrder(order: OrderVO) {
  try {
    await ElMessageBox.confirm(`确定取消订单「${order.productTitle}」吗？取消后商品将恢复为在售状态。`, '取消订单', { type: 'warning', confirmButtonText: '取消订单', cancelButtonText: '再想想' })
  } catch {
    return // 用户取消
  }
  actingId.value = order.id
  try {
    await orderApi.cancel(order.id)
    ElMessage.success('订单已取消')
    await loadOrders()
    if (detail.value?.id === order.id) detail.value = undefined
    detailVisible.value = false
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '取消失败'))
  } finally {
    actingId.value = null
  }
}

onMounted(loadOrders)
</script>
<template>
  <el-skeleton :loading="loading" animated :rows="5"><template #default>
    <div v-if="orders.length" class="order-list">
      <div v-for="order in orders" :key="order.id" class="order-row section-card">
        <div class="order-image">{{ order.productImage ? '商品图片' : '暂无图片' }}</div>
        <div class="order-info">
          <div class="order-top"><h2 @click="router.push({ name: 'product-detail', params: { id: order.productId } })">{{ order.productTitle }}</h2><el-tag size="small">{{ getStatusText(orderStatusText, order.status) }}</el-tag></div>
          <div class="order-meta"><strong>{{ formatPrice(order.productPrice) }}</strong><span>{{ isBuy() ? '卖家' : '买家' }}：{{ isBuy() ? (order.sellerNickname || '校园卖家') : (order.buyerNickname || '校园买家') }}</span><span>单号 {{ order.orderNo }}</span><span>{{ formatDate(order.createTime) }}</span></div>
        </div>
        <div class="order-actions">
          <el-button size="small" type="primary" link @click="openDetail(order)">查看详情</el-button>
          <el-button v-if="order.status === 0 && !isBuy()" size="small" type="success" :loading="actingId === order.id" @click="confirmOrder(order)">确认订单</el-button>
          <el-button v-if="order.status === 0" size="small" :loading="actingId === order.id" @click="cancelOrder(order)">取消订单</el-button>
        </div>
      </div>
    </div>
    <div v-else class="section-card"><EmptyState :title="isBuy() ? '还没有买到任何商品' : '还没有卖出任何商品'" :description="isBuy() ? '去商品广场逛逛，下单后会出现在这里。' : '商品被其他同学下单后会出现在这里。'" /></div>
  </template></el-skeleton>
  <el-pagination v-if="total > pageSize" background layout="prev, pager, next" :current-page="pageNo" :page-size="pageSize" :total="total" @current-change="changePage" />

  <el-dialog v-model="detailVisible" title="订单详情" width="480px">
    <el-skeleton v-if="detailLoading" :rows="4" animated />
    <div v-else-if="detail" class="order-detail">
      <dl>
        <div><dt>订单编号</dt><dd>{{ detail.orderNo }}</dd></div>
        <div><dt>商品</dt><dd>{{ detail.productTitle }}</dd></div>
        <div><dt>成交价</dt><dd><strong>{{ formatPrice(detail.productPrice) }}</strong></dd></div>
        <div><dt>买家</dt><dd>{{ detail.buyerNickname || '校园买家' }}</dd></div>
        <div><dt>卖家</dt><dd>{{ detail.sellerNickname || '校园卖家' }}</dd></div>
        <div><dt>状态</dt><dd><el-tag size="small">{{ getStatusText(orderStatusText, detail.status) }}</el-tag></dd></div>
        <div><dt>下单时间</dt><dd>{{ formatDate(detail.createTime) }}</dd></div>
      </dl>
      <div v-if="detail.status === 0" class="detail-actions">
        <el-button v-if="!isBuy()" type="success" :loading="actingId === detail.id" @click="confirmOrder(detail)">确认订单</el-button>
        <el-button :loading="actingId === detail.id" @click="cancelOrder(detail)">取消订单</el-button>
      </div>
    </div>
  </el-dialog>
</template>
<style scoped>
.order-list { display: grid; gap: 12px; }
.order-row { display: grid; grid-template-columns: 120px 1fr auto; align-items: center; gap: 16px; padding: 14px 18px; }
.order-image { display: grid; aspect-ratio: 1.4; place-items: center; border-radius: 10px; background: #eaf2ff; color: #64748b; font-size: 13px; }
.order-info { display: grid; gap: 6px; min-width: 0; }
.order-top { display: flex; align-items: center; gap: 10px; }
.order-top h2 { overflow: hidden; margin: 0; font-size: 16px; cursor: pointer; text-overflow: ellipsis; white-space: nowrap; }
.order-top h2:hover { color: #2563eb; }
.order-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; color: #94a3b8; font-size: 13px; }
.order-meta strong { color: #dc2626; font-size: 16px; }
.order-actions { display: flex; align-items: center; gap: 4px; }
.order-detail dl { display: grid; gap: 10px; margin: 0; }
.order-detail dl div { display: flex; gap: 14px; }
.order-detail dt { width: 72px; color: #94a3b8; }
.order-detail dd { margin: 0; color: #475569; word-break: break-all; }
.detail-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
@media (max-width: 700px) { .order-row { grid-template-columns: 80px 1fr; }.order-actions { grid-column: 1 / -1; } }
</style>
