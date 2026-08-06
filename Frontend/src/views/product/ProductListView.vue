<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PageContainer from '@/components/PageContainer.vue'
import { categoryApi } from '@/api/category'
import { productApi } from '@/api/product'
import { getStatusText, productStatusText } from '@/constants/status'
import { formatPrice, getErrorMessage } from '@/utils/format'
import type { CategoryVO } from '@/types/category'
import type { ProductVO } from '@/types/product'

const loading = ref(false)
const products = ref<ProductVO[]>([])
const total = ref(0)
const categories = ref<CategoryVO[]>([])
const query = reactive({ pageNo: 1, pageSize: 10, keyword: '', categoryId: undefined as number | undefined, sort: 'latest' as const })

async function loadCategories() {
  try {
    categories.value = await categoryApi.list()
  } catch {
    // 分类下拉是辅助筛选，加载失败静默处理，不影响商品列表。
  }
}

async function loadProducts() {
  loading.value = true
  try {
    const page = await productApi.list({ ...query, keyword: query.keyword || undefined, categoryId: query.categoryId || undefined })
    products.value = page.records
    total.value = page.total
  } catch (error) {
    products.value = []
    ElMessage.error(getErrorMessage(error, '商品列表加载失败'))
  } finally { loading.value = false }
}
function search() { query.pageNo = 1; void loadProducts() }
function filterCategory() { query.pageNo = 1; void loadProducts() }
function changePage(pageNo: number) { query.pageNo = pageNo; void loadProducts() }
onMounted(() => { void loadCategories(); void loadProducts() })
</script>
<template>
  <PageContainer><section><h1 class="page-title">商品广场</h1><p class="page-subtitle">浏览校园闲置商品；分类、价格区间和成色筛选将在下一步接入。</p></section>
    <el-form class="section-card filter-bar" inline @submit.prevent="search"><el-form-item label="关键词"><el-input v-model.trim="query.keyword" clearable placeholder="搜索商品标题" /></el-form-item><el-form-item label="分类"><el-select v-model="query.categoryId" clearable placeholder="全部分类" style="width: 160px" @change="filterCategory"><el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item><el-form-item><el-button type="primary" @click="search">搜索</el-button></el-form-item></el-form>
    <el-skeleton :loading="loading" animated :rows="6"><template #default><div v-if="products.length" class="product-grid"><RouterLink v-for="product in products" :key="product.id" class="product-card" :to="{ name: 'product-detail', params: { id: product.id } }"><div class="product-image">{{ product.image ? '商品图片' : '暂无图片' }}</div><div class="product-content"><h2>{{ product.title }}</h2><strong>{{ formatPrice(product.price) }}</strong><div><el-tag size="small">{{ getStatusText(productStatusText, product.status) }}</el-tag><span>{{ product.sellerNickname || '校园卖家' }}</span></div></div></RouterLink></div><div v-else class="section-card"><EmptyState description="调整关键词后再试，或确认后端服务已启动。" /></div></template></el-skeleton>
    <el-pagination v-if="total > query.pageSize" background layout="prev, pager, next" :current-page="query.pageNo" :page-size="query.pageSize" :total="total" @current-change="changePage" />
  </PageContainer>
</template>
<style scoped>
.filter-bar { margin: 0; padding: 16px 18px 0; }.product-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }.product-card { overflow: hidden; border: 1px solid #e7edf5; border-radius: 14px; background: #fff; transition: transform .2s, box-shadow .2s; }.product-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgb(30 64 175 / 10%); }.product-image { display: grid; aspect-ratio: 1.2; place-items: center; background: #eaf2ff; color: #64748b; }.product-content { display: grid; gap: 10px; padding: 16px; }.product-content h2 { overflow: hidden; margin: 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }.product-content strong { color: #dc2626; font-size: 18px; }.product-content div { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #64748b; font-size: 13px; } @media (max-width: 900px) { .product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 480px) { .product-grid { grid-template-columns: 1fr; } }
</style>
