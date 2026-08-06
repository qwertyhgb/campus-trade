<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import CommentSection from '@/components/CommentSection.vue'
import EmptyState from '@/components/EmptyState.vue'
import { favoriteApi } from '@/api/favorite'
import { orderApi } from '@/api/order'
import { productApi } from '@/api/product'
import { useAuthStore } from '@/stores/auth'
import { conditionLevelText, getStatusText, productStatusText } from '@/constants/status'
import { formatDate, formatPrice, getErrorMessage } from '@/utils/format'
import type { ProductVO } from '@/types/product'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(true)
const product = ref<ProductVO>()
const isFavorited = ref(false)
const favoriteLoading = ref(false)
const placing = ref(false)

const isMine = computed(() => Boolean(product.value && authStore.user?.id === product.value.sellerId))

async function loadProduct() {
  loading.value = true
  try {
    product.value = await productApi.getById(String(route.params.id))
    await loadFavoriteStatus()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '商品详情加载失败'))
  } finally {
    loading.value = false
  }
}

// 收藏状态：登录后才查询（未登录查询会触发 401 跳转）；查询失败静默，按钮保持未收藏态。
async function loadFavoriteStatus() {
  if (!authStore.isLoggedIn || !product.value) return
  try {
    isFavorited.value = await favoriteApi.status(product.value.id)
  } catch {
    isFavorited.value = false
  }
}

function goLogin() {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}

async function toggleFavorite() {
  if (!authStore.isLoggedIn) { goLogin(); return }
  if (!product.value) return
  favoriteLoading.value = true
  try {
    if (isFavorited.value) {
      await favoriteApi.remove(product.value.id)
      isFavorited.value = false
      ElMessage.success('已取消收藏')
    } else {
      await favoriteApi.add(product.value.id)
      isFavorited.value = true
      ElMessage.success('收藏成功')
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '操作失败'))
  } finally {
    favoriteLoading.value = false
  }
}

async function placeOrder() {
  if (!authStore.isLoggedIn) { goLogin(); return }
  if (!product.value || placing.value) return
  try {
    await ElMessageBox.confirm(
      `确认购买「${product.value.title}」？\n价格：${formatPrice(product.value.price)}\n下单后商品将被锁定，请与卖家确认交易。`,
      '确认下单',
      { type: 'warning', confirmButtonText: '确认下单', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户取消
  }
  placing.value = true
  try {
    await orderApi.place({ productId: product.value.id })
    ElMessage.success('下单成功，请等待卖家确认')
    await loadProduct() // 商品已锁定，重新拉取真实状态
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '下单失败'))
  } finally {
    placing.value = false
  }
}

onMounted(loadProduct)
</script>
<template><el-skeleton :loading="loading" animated :rows="8"><template #default>
  <section v-if="product" class="detail-card section-card"><div class="product-image">{{ product.image ? '商品图片区域' : '暂无图片' }}</div><div class="detail-content"><el-tag>{{ getStatusText(productStatusText, product.status) }}</el-tag><h1>{{ product.title }}</h1><strong>{{ formatPrice(product.price) }}</strong><p>{{ product.description }}</p><dl><div><dt>卖家</dt><dd>{{ product.sellerNickname || '校园卖家' }}</dd></div><div><dt>成色</dt><dd>{{ getStatusText(conditionLevelText, product.conditionLevel ?? -1) }}</dd></div><div><dt>浏览量</dt><dd>{{ product.viewCount ?? 0 }}</dd></div><div><dt>发布时间</dt><dd>{{ formatDate(product.createTime) }}</dd></div></dl><div class="buy-actions">
    <el-button :type="isFavorited ? 'warning' : 'default'" :loading="favoriteLoading" @click="toggleFavorite">{{ isFavorited ? '已收藏' : '收藏' }}</el-button>
    <template v-if="product.status === 1">
      <el-button v-if="!authStore.isLoggedIn" type="primary" @click="goLogin">登录后购买</el-button>
      <el-tooltip v-else :content="isMine ? '不能购买自己发布的商品' : ''" placement="top"><span><el-button type="primary" :disabled="isMine" :loading="placing" @click="placeOrder">立即购买</el-button></span></el-tooltip>
    </template>
    <el-tag v-else-if="product.status === 2" type="warning">商品已被锁定，交易进行中</el-tag>
  </div></div></section>
  <div v-else class="section-card"><EmptyState title="商品不存在或无法访问" /></div>
  <CommentSection v-if="product" :product-id="product.id" />
</template></el-skeleton></template>
<style scoped>
.detail-card { display: grid; grid-template-columns: minmax(280px, .9fr) minmax(0, 1.1fr); overflow: hidden; }
.product-image { display: grid; min-height: 340px; place-items: center; background: #eaf2ff; color: #64748b; }
.detail-content { display: grid; align-content: start; gap: 16px; padding: 36px; }
.detail-content h1, .detail-content p { margin: 0; }
.detail-content h1 { color: #172033; font-size: 28px; }
.detail-content strong { color: #dc2626; font-size: 28px; }
.detail-content p { color: #475569; line-height: 1.8; white-space: pre-wrap; }
dl { display: grid; gap: 8px; margin: 0; } dl div { display: flex; gap: 16px; } dt { width: 72px; color: #94a3b8; } dd { margin: 0; color: #475569; }
.buy-actions { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
@media (max-width: 700px) { .detail-card { grid-template-columns: 1fr; }.product-image { min-height: 230px; }.detail-content { padding: 24px; } }
</style>
