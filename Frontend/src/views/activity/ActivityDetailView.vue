<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { activityApi } from '@/api/activity'
import { activityStatusText, getStatusText } from '@/constants/status'
import { formatDate, getErrorMessage } from '@/utils/format'
import type { ActivityDetailVO } from '@/types/activity'

const route = useRoute()
const loading = ref(true)
const activity = ref<ActivityDetailVO>()
async function loadActivity() {
  loading.value = true
  try { activity.value = await activityApi.getById(String(route.params.id)) }
  catch (error) { ElMessage.error(getErrorMessage(error, '活动详情加载失败')) }
  finally { loading.value = false }
}
onMounted(loadActivity)
</script>
<template><el-skeleton :loading="loading" animated :rows="8"><template #default><section v-if="activity" class="detail-card section-card"><div class="cover">{{ activity.coverImage ? '活动封面区域' : '暂无封面' }}</div><div class="detail-content"><el-tag type="success">{{ getStatusText(activityStatusText, activity.status) }}</el-tag><h1>{{ activity.title }}</h1><p>{{ activity.description }}</p><dl><div><dt>组织者</dt><dd>{{ activity.organizerNickname || '校园组织者' }}</dd></div><div><dt>活动地点</dt><dd>{{ activity.location }}</dd></div><div><dt>活动时间</dt><dd>{{ formatDate(activity.startTime) }} 至 {{ formatDate(activity.endTime) }}</dd></div><div><dt>报名时间</dt><dd>{{ formatDate(activity.enrollStartTime) }} 至 {{ formatDate(activity.enrollEndTime) }}</dd></div><div><dt>报名人数</dt><dd>{{ activity.currentCount }}/{{ activity.maxCount }}</dd></div><div><dt>候补人数</dt><dd>{{ activity.waitingListCount ?? 0 }}</dd></div></dl><el-button type="primary" :disabled="activity.status !== 3">预约功能待接入</el-button></div></section><div v-else class="section-card"><EmptyState title="活动不存在或无法访问" /></div></template></el-skeleton></template>
<style scoped>.detail-card { display: grid; grid-template-columns: minmax(280px, .9fr) minmax(0, 1.1fr); overflow: hidden; }.cover { display: grid; min-height: 340px; place-items: center; background: #ecfeff; color: #64748b; }.detail-content { display: grid; align-content: start; gap: 16px; padding: 36px; }.detail-content h1, .detail-content p { margin: 0; }.detail-content h1 { color: #172033; font-size: 28px; }.detail-content p { color: #475569; line-height: 1.8; white-space: pre-wrap; } dl { display: grid; gap: 8px; margin: 0; } dl div { display: flex; gap: 16px; } dt { width: 72px; color: #94a3b8; } dd { margin: 0; color: #475569; } @media (max-width: 700px) { .detail-card { grid-template-columns: 1fr; }.cover { min-height: 230px; }.detail-content { padding: 24px; } }</style>
