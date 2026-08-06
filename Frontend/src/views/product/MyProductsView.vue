<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PageContainer from '@/components/PageContainer.vue'
import { productApi } from '@/api/product'
import { getStatusText, productStatusText } from '@/constants/status'
import { formatDate, formatPrice, getErrorMessage } from '@/utils/format'
import type { ProductVO } from '@/types/product'

const router = useRouter()
const loading = ref(false)
const products = ref<ProductVO[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const actingId = ref<number | null>(null)

async function loadProducts() {
  loading.value = true
  try {
    const page = await productApi.my(pageNo.value, pageSize)
    products.value = page.records
    total.value = page.total
  } catch (error) {
    products.value = []
    ElMessage.error(getErrorMessage(error, '商品列表加载失败'))
  } finally {
    loading.value = false
  }
}

function changePage(next: number) { pageNo.value = next; void loadProducts() }

async function changeStatus(product: ProductVO, status: number, actionText: string) {
  try {
    await ElMessageBox.confirm(
      status === 0 ? `确定下架「${product.title}」吗？下架后商品不再对外展示。` : `确定将「${product.title}」重新提交审核吗？`,
      actionText,
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  actingId.value = product.id
  try {
    await productApi.updateStatus(product.id, status)
    ElMessage.success(status === 0 ? '已下架' : '已重新提交审核')
    await loadProducts()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '操作失败'))
  } finally {
    actingId.value = null
  }
}

onMounted(loadProducts)
</script>
<template>
  <PageContainer>
    <section class="page-head"><div><h1 class="page-title">我的商品</h1><p class="page-subtitle">管理自己发布的商品，编辑、下架或重新提交审核。</p></div><el-button type="primary" @click="router.push({ name: 'product-publish' })">发布商品</el-button></section>
    <el-skeleton :loading="loading" animated :rows="5"><template #default>
      <div v-if="products.length" class="product-list">
        <div v-for="product in products" :key="product.id" class="product-row section-card">
          <div class="product-image">{{ product.image ? '商品图片' : '暂无图片' }}</div>
          <div class="product-info">
            <div class="product-top"><h2>{{ product.title }}</h2><el-tag size="small">{{ getStatusText(productStatusText, product.status) }}</el-tag></div>
            <p v-if="product.reviewRemark" class="review-remark">审核备注：{{ product.reviewRemark }}</p>
            <div class="product-meta"><strong>{{ formatPrice(product.price) }}</strong><span>发布于 {{ formatDate(product.createTime) }}</span></div>
          </div>
          <div class="product-actions">
            <el-button size="small" @click="router.push({ name: 'product-edit', params: { id: product.id } })">编辑</el-button>
            <el-button v-if="product.status === 1" size="small" :loading="actingId === product.id" @click="changeStatus(product, 0, '下架确认')">下架</el-button>
            <el-button v-if="product.status === 0 || product.status === 5" size="small" type="primary" plain :loading="actingId === product.id" @click="changeStatus(product, 4, '重新提交审核')">重新提交审核</el-button>
            <el-button size="small" type="primary" link @click="router.push({ name: 'product-detail', params: { id: product.id } })">查看</el-button>
          </div>
        </div>
      </div>
      <div v-else class="section-card"><EmptyState title="还没有发布过商品" description="发布第一件闲置，开始你的校园交易之旅。" /></div>
    </template></el-skeleton>
    <el-pagination v-if="total > pageSize" background layout="prev, pager, next" :current-page="pageNo" :page-size="pageSize" :total="total" @current-change="changePage" />
  </PageContainer>
</template>
<style scoped>
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.product-list { display: grid; gap: 12px; }
.product-row { display: grid; grid-template-columns: 120px 1fr auto; align-items: center; gap: 16px; padding: 14px 18px; }
.product-image { display: grid; aspect-ratio: 1.4; place-items: center; border-radius: 10px; background: #eaf2ff; color: #64748b; font-size: 13px; }
.product-info { display: grid; gap: 6px; min-width: 0; }
.product-top { display: flex; align-items: center; gap: 10px; }
.product-top h2 { overflow: hidden; margin: 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.review-remark { margin: 0; color: #dc2626; font-size: 13px; }
.product-meta { display: flex; align-items: center; gap: 16px; color: #94a3b8; font-size: 13px; }
.product-meta strong { color: #dc2626; font-size: 16px; }
.product-actions { display: flex; align-items: center; gap: 4px; }
@media (max-width: 700px) { .product-row { grid-template-columns: 80px 1fr; }.product-actions { grid-column: 1 / -1; } }
</style>
