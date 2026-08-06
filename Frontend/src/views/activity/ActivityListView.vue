<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import PageContainer from '@/components/PageContainer.vue'
import { activityCategoryApi } from '@/api/category'
import { activityApi } from '@/api/activity'
import { activityStatusText, getStatusText, publicActivityStatusOptions } from '@/constants/status'
import { formatDate, getErrorMessage } from '@/utils/format'
import type { ActivityCategoryVO } from '@/types/category'
import type { ActivityListItemVO } from '@/types/activity'

const loading = ref(false)
const activities = ref<ActivityListItemVO[]>([])
const total = ref(0)
const categories = ref<ActivityCategoryVO[]>([])
const dateRange = ref<[string, string] | null>(null)
const query = reactive({
  pageNo: 1,
  pageSize: 10,
  keyword: '',
  categoryId: undefined as number | undefined,
  status: undefined as number | undefined,
})

async function loadCategories() {
  try {
    categories.value = await activityCategoryApi.list()
  } catch {
    // 分类下拉是辅助筛选，加载失败静默处理，不影响活动列表。
  }
}

async function loadActivities() {
  loading.value = true
  try {
    const page = await activityApi.list({
      ...query,
      keyword: query.keyword || undefined,
      categoryId: query.categoryId || undefined,
      status: query.status,
      // 时间范围筛选：开始时间在 [startTimeFrom, startTimeTo] 闭区间内（ISO-8601 格式，与后端 LocalDateTime 解析兼容）
      startTimeFrom: dateRange.value?.[0] || undefined,
      startTimeTo: dateRange.value?.[1] || undefined,
    })
    activities.value = page.records
    total.value = page.total
  } catch (error) {
    activities.value = []
    ElMessage.error(getErrorMessage(error, '活动列表加载失败'))
  } finally {
    loading.value = false
  }
}
function search() { query.pageNo = 1; void loadActivities() }
function filterChanged() { query.pageNo = 1; void loadActivities() }
function resetFilters() {
  query.keyword = ''
  query.categoryId = undefined
  query.status = undefined
  dateRange.value = null
  search()
}
function changePage(pageNo: number) { query.pageNo = pageNo; void loadActivities() }
onMounted(() => { void loadCategories(); void loadActivities() })
</script>
<template><PageContainer><section><h1 class="page-title">校园活动</h1><p class="page-subtitle">按分类、状态和时间范围筛选感兴趣的活动。</p></section>
  <el-form class="section-card filter-bar" inline @submit.prevent="search">
    <el-form-item label="关键词"><el-input v-model.trim="query.keyword" clearable placeholder="搜索活动标题" /></el-form-item>
    <el-form-item label="分类"><el-select v-model="query.categoryId" clearable placeholder="全部分类" style="width: 160px" @change="filterChanged"><el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item>
    <el-form-item label="状态"><el-select v-model="query.status" clearable placeholder="全部状态" style="width: 140px" @change="filterChanged"><el-option v-for="option in publicActivityStatusOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
    <el-form-item label="开始时间"><el-date-picker v-model="dateRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 360px" @change="filterChanged" /></el-form-item>
    <el-form-item><el-button type="primary" @click="search">搜索</el-button><el-button @click="resetFilters">重置</el-button></el-form-item>
  </el-form>
  <el-skeleton :loading="loading" animated :rows="6"><template #default><div v-if="activities.length" class="activity-list">
    <RouterLink v-for="activity in activities" :key="activity.id" class="activity-card" :to="{ name: 'activity-detail', params: { id: activity.id } }"><div class="cover">{{ activity.coverImage ? '活动封面' : '暂无封面' }}</div><div class="activity-content"><div><el-tag size="small" type="success">{{ getStatusText(activityStatusText, activity.status) }}</el-tag><span>{{ activity.categoryName || '校园活动' }}</span></div><h2>{{ activity.title }}</h2><p>{{ activity.location }} · {{ formatDate(activity.startTime) }}</p><span>{{ activity.currentCount }}/{{ activity.maxCount }} 人已报名</span></div></RouterLink>
  </div><div v-else class="section-card"><EmptyState description="调整筛选条件后再试，或确认后端服务已启动。" /></div></template></el-skeleton>
  <el-pagination v-if="total > query.pageSize" background layout="prev, pager, next" :current-page="query.pageNo" :page-size="query.pageSize" :total="total" @current-change="changePage" />
</PageContainer></template>
<style scoped>.filter-bar { margin: 0; padding: 16px 18px 0; }.activity-list { display: grid; gap: 14px; }.activity-card { display: grid; grid-template-columns: 180px 1fr; overflow: hidden; border: 1px solid #e7edf5; border-radius: 14px; background: #fff; transition: box-shadow .2s; }.activity-card:hover { box-shadow: 0 10px 24px rgb(30 64 175 / 10%); }.cover { display: grid; min-height: 140px; place-items: center; background: #ecfeff; color: #64748b; }.activity-content { display: grid; align-content: center; gap: 10px; padding: 18px 22px; }.activity-content > div { display: flex; gap: 8px; color: #64748b; font-size: 13px; }.activity-content h2, .activity-content p { margin: 0; }.activity-content h2 { color: #172033; font-size: 18px; }.activity-content p, .activity-content > span { color: #64748b; } @media (max-width: 600px) { .activity-card { grid-template-columns: 1fr; }.cover { min-height: 180px; } }</style>
