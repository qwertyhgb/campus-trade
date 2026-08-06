<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PageContainer from '@/components/PageContainer.vue'
import { favoriteApi } from '@/api/favorite'
import { getStatusText, productStatusText } from '@/constants/status'
import { formatDate, formatPrice, getErrorMessage } from '@/utils/format'
import type { FavoriteVO } from '@/types/favorite'

const router = useRouter()
const loading = ref(false)
const favorites = ref<FavoriteVO[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 10
const removingId = ref<number | null>(null)

async function loadFavorites() {
  loading.value = true
  try {
    const page = await favoriteApi.my(pageNo.value, pageSize)
    favorites.value = page.records
    total.value = page.total
  } catch (error) {
    favorites.value = []
    ElMessage.error(getErrorMessage(error, '收藏列表加载失败'))
  } finally {
    loading.value = false
  }
}

function changePage(next: number) { pageNo.value = next; void loadFavorites() }

async function removeFavorite(item: FavoriteVO) {
  try {
    await ElMessageBox.confirm(`确定取消收藏「${item.productTitle}」吗？`, '取消收藏', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  } catch {
    return // 用户取消
  }
  removingId.value = item.productId
  try {
    await favoriteApi.remove(item.productId)
    ElMessage.success('已取消收藏')
    await loadFavorites()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '取消收藏失败'))
  } finally {
    removingId.value = null
  }
}

onMounted(loadFavorites)
</script>
<template>
  <PageContainer>
    <section><h1 class="page-title">我的收藏</h1><p class="page-subtitle">收藏的商品会保留当前状态；已下架或已售出的商品仅可查看。</p></section>
    <el-skeleton :loading="loading" animated :rows="5"><template #default>
      <div v-if="favorites.length" class="favorite-list">
        <div v-for="item in favorites" :key="item.id" class="favorite-row section-card">
          <div class="favorite-image">{{ item.productImage ? '商品图片' : '暂无图片' }}</div>
          <div class="favorite-info">
            <div class="favorite-top"><h2 @click="router.push({ name: 'product-detail', params: { id: item.productId } })">{{ item.productTitle }}</h2><el-tag size="small">{{ getStatusText(productStatusText, item.productStatus) }}</el-tag></div>
            <div class="favorite-meta"><strong>{{ formatPrice(item.productPrice) }}</strong><span>{{ item.sellerNickname || '校园卖家' }}</span><span>收藏于 {{ formatDate(item.createTime) }}</span></div>
          </div>
          <div class="favorite-actions">
            <el-button size="small" type="primary" link @click="router.push({ name: 'product-detail', params: { id: item.productId } })">查看商品</el-button>
            <el-button size="small" :loading="removingId === item.productId" @click="removeFavorite(item)">取消收藏</el-button>
          </div>
        </div>
      </div>
      <div v-else class="section-card"><EmptyState title="还没有收藏任何商品" description="在商品详情页点击收藏，喜欢的商品会出现在这里。" /></div>
    </template></el-skeleton>
    <el-pagination v-if="total > pageSize" background layout="prev, pager, next" :current-page="pageNo" :page-size="pageSize" :total="total" @current-change="changePage" />
  </PageContainer>
</template>
<style scoped>
.favorite-list { display: grid; gap: 12px; }
.favorite-row { display: grid; grid-template-columns: 120px 1fr auto; align-items: center; gap: 16px; padding: 14px 18px; }
.favorite-image { display: grid; aspect-ratio: 1.4; place-items: center; border-radius: 10px; background: #eaf2ff; color: #64748b; font-size: 13px; }
.favorite-info { display: grid; gap: 6px; min-width: 0; }
.favorite-top { display: flex; align-items: center; gap: 10px; }
.favorite-top h2 { overflow: hidden; margin: 0; font-size: 16px; cursor: pointer; text-overflow: ellipsis; white-space: nowrap; }
.favorite-top h2:hover { color: #2563eb; }
.favorite-meta { display: flex; align-items: center; gap: 16px; color: #94a3b8; font-size: 13px; }
.favorite-meta strong { color: #dc2626; font-size: 16px; }
.favorite-actions { display: flex; align-items: center; gap: 4px; }
@media (max-width: 700px) { .favorite-row { grid-template-columns: 80px 1fr; }.favorite-actions { grid-column: 1 / -1; } }
</style>
