import { request } from './request'
import type { PageResult } from '@/types/api'
import type { CommentAddPayload, CommentVO } from '@/types/comment'

export const commentApi = {
  listByProduct: (productId: string | number, pageNo = 1, pageSize = 10) =>
    request.get<PageResult<CommentVO>>(`/comment/product/${productId}`, { params: { pageNo, pageSize } }),
  listReplies: (parentId: number, pageNo = 1, pageSize = 10) =>
    request.get<PageResult<CommentVO>>(`/comment/${parentId}/replies`, { params: { pageNo, pageSize } }),
  add: (payload: CommentAddPayload) => request.post<void>('/comment/add', payload),
  remove: (id: number) => request.delete<void>(`/comment/${id}`),
}
