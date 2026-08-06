import { request } from './request'
import type { PageResult } from '@/types/api'
import type { OrderPlacePayload, OrderVO } from '@/types/order'

export const orderApi = {
  place: (payload: OrderPlacePayload) => request.post<void>('/order/place', payload),
  getById: (id: number) => request.get<OrderVO>(`/order/${id}`),
  confirm: (id: number) => request.put<void>(`/order/${id}/confirm`),
  cancel: (id: number) => request.put<void>(`/order/${id}/cancel`),
  buy: (pageNo = 1, pageSize = 10) => request.get<PageResult<OrderVO>>('/order/buy', { params: { pageNo, pageSize } }),
  sell: (pageNo = 1, pageSize = 10) => request.get<PageResult<OrderVO>>('/order/sell', { params: { pageNo, pageSize } }),
}
