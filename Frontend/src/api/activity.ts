import { request } from './request'
import type { PageResult } from '@/types/api'
import type { ActivityDetailVO, ActivityListItemVO, ActivityQuery } from '@/types/activity'

export const activityApi = {
  list: (params: ActivityQuery) => request.get<PageResult<ActivityListItemVO>>('/activity/list', { params }),
  hot: (limit = 6) => request.get<ActivityListItemVO[]>('/activity/hot', { params: { limit } }),
  getById: (id: string | number) => request.get<ActivityDetailVO>(`/activity/${id}`),
}
