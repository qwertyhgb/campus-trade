import { request } from './request'
import type { PageResult } from '@/types/api'
import type { ProductPublishPayload, ProductQuery, ProductUpdatePayload, ProductVO } from '@/types/product'

export const productApi = {
  list: (params: ProductQuery) => request.get<PageResult<ProductVO>>('/product/list', { params }),
  getById: (id: string | number) => request.get<ProductVO>(`/product/${id}`),
  publish: (payload: ProductPublishPayload) => request.post<void>('/product/publish', payload),
  update: (id: number, payload: ProductUpdatePayload) => request.put<void>(`/product/${id}`, payload),
  /** 我的商品列表（含任意状态） */
  my: (pageNo = 1, pageSize = 10) => request.get<PageResult<ProductVO>>('/product/my', { params: { pageNo, pageSize } }),
  /** 我的商品私有详情（含审核备注），编辑页专用 */
  getMyById: (id: string | number) => request.get<ProductVO>(`/product/my/${id}`),
  /** 修改商品状态：0=下架，4=重新提交审核 */
  updateStatus: (id: number, status: number) => request.post<void>(`/product/${id}/status`, undefined, { params: { status } }),
}
