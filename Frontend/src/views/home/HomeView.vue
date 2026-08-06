<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { activityApi } from '@/api/activity'
import { productApi } from '@/api/product'
import { activityStatusText, getStatusText, productStatusText } from '@/constants/status'
import { formatDate, formatPrice, getErrorMessage } from '@/utils/format'
import type { ActivityListItemVO } from '@/types/activity'
import type { ProductVO } from '@/types/product'

// 热门活动区：装饰性区块，加载失败时静默隐藏，不影响首页其他内容。
const hotActivities = ref<ActivityListItemVO[]>([])
const hotLoading = ref(false)
const hotError = ref(false)

// 最新商品区：主区块，加载失败时展示错误提示与重试入口。
const latestProducts = ref<ProductVO[]>([])
const latestLoading = ref(false)
const latestError = ref(false)

async function loadHotActivities() {
  hotLoading.value = true
  hotError.value = false
  try {
    hotActivities.value = await activityApi.hot(6)
  } catch {
    hotError.value = true
    hotActivities.value = []
  } finally {
    hotLoading.value = false
  }
}

async function loadLatestProducts() {
  latestLoading.value = true
  latestError.value = false
  try {
    const page = await productApi.list({ pageNo: 1, pageSize: 8, sort: 'latest' })
    latestProducts.value = page.records
  } catch (error) {
    latestError.value = true
    latestProducts.value = []
    ElMessage.error(getErrorMessage(error, '最新商品加载失败'))
  } finally {
    latestLoading.value = false
  }
}

const featureCards = [
  { title: '二手交易', description: '浏览商品、发布闲置、收藏与下单。', routeName: 'product-list' },
  { title: '校园活动', description: '查看活动、预约报名、满员后加入候补。', routeName: 'activity-list' },
  { title: '个人中心', description: '管理商品、订单、预约与通知。', routeName: 'profile' },
]

onMounted(() => {
  // 两个请求互不依赖，并行触发；一个失败不能阻塞另一个。
  void loadHotActivities()
  void loadLatestProducts()
})
</script>
<template>
  <section class="hero"><div><span class="eyebrow">CAMPUS TRADE</span><h1>让闲置流转，让校园活动更好参与。</h1><p>这是一个用于学习 Vue 前后端分离开发的本地项目，当前已建立页面、接口和权限骨架。</p><div class="hero-actions"><el-button type="primary" size="large" @click="$router.push({ name: 'product-list' })">浏览商品</el-button><el-button size="large" @click="$router.push({ name: 'activity-list' })">查看活动</el-button></div></div><div class="hero-stat-grid"><div><strong>商品</strong><span>发布、审核、交易</span></div><div><strong>活动</strong><span>报名、候补、通知</span></div><div><strong>角色</strong><span>用户与管理后台</span></div></div></section>

  <section v-if="!hotError && (hotLoading || hotActivities.length)" class="page-section">
    <div class="section-head"><h2 class="page-title">热门活动</h2><RouterLink :to="{ name: 'activity-list' }">查看全部 →</RouterLink></div>
    <el-skeleton :loading="hotLoading" animated :rows="3"><div v-if="hotActivities.length" class="hot-grid">
      <RouterLink v-for="activity in hotActivities" :key="activity.id" class="hot-card" :to="{ name: 'activity-detail', params: { id: activity.id } }">
        <div class="hot-cover">{{ activity.coverImage ? '活动封面' : '暂无封面' }}</div>
        <div class="hot-content">
          <div><el-tag size="small" type="success">{{ getStatusText(activityStatusText, activity.status) }}</el-tag><span>{{ activity.categoryName || '校园活动' }}</span></div>
          <h3>{{ activity.title }}</h3>
          <p>{{ activity.location }} · {{ formatDate(activity.startTime) }}</p>
          <span>{{ activity.currentCount }}/{{ activity.maxCount }} 人已报名</span>
        </div>
      </RouterLink>
    </div></el-skeleton>
  </section>

  <section class="page-section">
    <div class="section-head"><h2 class="page-title">最新商品</h2><RouterLink :to="{ name: 'product-list' }">查看全部 →</RouterLink></div>
    <el-skeleton :loading="latestLoading" animated :rows="4"><template #default>
      <div v-if="latestProducts.length" class="product-grid">
        <RouterLink v-for="product in latestProducts" :key="product.id" class="product-card" :to="{ name: 'product-detail', params: { id: product.id } }">
          <div class="product-image">{{ product.image ? '商品图片' : '暂无图片' }}</div>
          <div class="product-content">
            <h3>{{ product.title }}</h3>
            <strong>{{ formatPrice(product.price) }}</strong>
            <div><el-tag size="small">{{ getStatusText(productStatusText, product.status) }}</el-tag><span>{{ product.sellerNickname || '校园卖家' }}</span></div>
          </div>
        </RouterLink>
      </div>
      <div v-else-if="latestError" class="load-error section-card"><span>最新商品加载失败，请检查后端服务。</span><el-button size="small" @click="loadLatestProducts">重试</el-button></div>
      <div v-else class="section-card"><EmptyState description="还没有发布任何商品，去发布第一件闲置吧。" /></div>
    </template></el-skeleton>
  </section>

  <section class="page-section"><h2 class="page-title">从核心功能开始</h2><p class="page-subtitle">按照开发计划逐步补齐每一个业务闭环。</p><div class="feature-grid"><RouterLink v-for="card in featureCards" :key="card.title" class="feature-card" :to="{ name: card.routeName }"><h3>{{ card.title }}</h3><p>{{ card.description }}</p><span>进入模块 →</span></RouterLink></div></section>
</template>
<style scoped>
.hero { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(280px, .7fr); gap: 32px; padding: 48px; border-radius: 20px; background: linear-gradient(135deg, #172554, #1d4ed8 55%, #0f766e); color: #eff6ff; }.eyebrow { color: #a5f3fc; font-size: 12px; font-weight: 700; letter-spacing: .16em; }.hero h1 { max-width: 650px; margin: 12px 0 16px; font-size: clamp(32px, 5vw, 50px); line-height: 1.16; }.hero p { max-width: 600px; margin: 0; color: #dbeafe; line-height: 1.8; }.hero-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 26px; }.hero-stat-grid { display: grid; gap: 12px; }.hero-stat-grid > div { display: grid; gap: 5px; padding: 18px; border: 1px solid rgb(191 219 254 / 28%); border-radius: 12px; background: rgb(255 255 255 / 10%); }.hero-stat-grid strong { font-size: 18px; }.hero-stat-grid span { color: #bfdbfe; font-size: 14px; }
.page-section { margin-top: 28px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }.section-head a { color: #2563eb; font-size: 14px; font-weight: 600; }
.hot-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }.hot-card { overflow: hidden; border: 1px solid #e7edf5; border-radius: 14px; background: #fff; transition: transform .2s, box-shadow .2s; }.hot-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgb(30 64 175 / 10%); }.hot-cover { display: grid; aspect-ratio: 2.4; place-items: center; background: #ecfeff; color: #64748b; }.hot-content { display: grid; gap: 10px; padding: 16px; }.hot-content > div { display: flex; align-items: center; gap: 8px; color: #64748b; font-size: 13px; }.hot-content h3 { overflow: hidden; margin: 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }.hot-content p { overflow: hidden; margin: 0; text-overflow: ellipsis; white-space: nowrap; }.hot-content p, .hot-content > span { color: #64748b; font-size: 13px; }
.product-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }.product-card { overflow: hidden; border: 1px solid #e7edf5; border-radius: 14px; background: #fff; transition: transform .2s, box-shadow .2s; }.product-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgb(30 64 175 / 10%); }.product-image { display: grid; aspect-ratio: 1.2; place-items: center; background: #eaf2ff; color: #64748b; }.product-content { display: grid; gap: 10px; padding: 16px; }.product-content h3 { overflow: hidden; margin: 0; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }.product-content strong { color: #dc2626; font-size: 18px; }.product-content div { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #64748b; font-size: 13px; }
.load-error { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 28px; color: #64748b; }
.feature-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }.feature-card { display: grid; gap: 10px; padding: 22px; border: 1px solid #e7edf5; border-radius: 14px; background: #fff; transition: transform .2s, box-shadow .2s; }.feature-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgb(30 64 175 / 10%); }.feature-card h3, .feature-card p { margin: 0; }.feature-card p { color: #64748b; line-height: 1.7; }.feature-card span { color: #2563eb; font-weight: 600; }
@media (max-width: 800px) { .hero, .feature-grid { grid-template-columns: 1fr; }.hero { padding: 30px 24px; } }
@media (max-width: 900px) { .hot-grid, .product-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 480px) { .hot-grid, .product-grid { grid-template-columns: 1fr; } }
</style>
