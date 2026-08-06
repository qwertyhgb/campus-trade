import { request } from './request'
import type { PageResult } from '@/types/api'
import type { FavoriteVO } from '@/types/favorite'

export const favoriteApi = {
  add: (productId: number) => request.post<void>(`/favorite/${productId}`),
  remove: (productId: number) => request.delete<void>(`/favorite/${productId}`),
  status: (productId: number) => request.get<boolean>(`/favorite/${productId}/status`),
  my: (pageNo = 1, pageSize = 10) => request.get<PageResult<FavoriteVO>>('/favorite/my', { params: { pageNo, pageSize } }),
}
