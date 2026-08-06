import { request } from './request'
import type { ActivityCategoryVO, CategoryVO } from '@/types/category'

export const categoryApi = {
  list: () => request.get<CategoryVO[]>('/category/list'),
}

export const activityCategoryApi = {
  list: () => request.get<ActivityCategoryVO[]>('/activity-category/list'),
}
