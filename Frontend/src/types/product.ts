export interface ProductQuery {
  pageNo?: number
  pageSize?: number
  keyword?: string
  categoryId?: number
  minPrice?: number
  maxPrice?: number
  conditionLevel?: number
  sort?: 'latest' | 'price_asc' | 'price_desc'
}

export interface ProductVO {
  id: number
  title: string
  description: string
  price: number
  originalPrice?: number
  image?: string
  categoryId?: number
  sellerId: number
  sellerNickname?: string
  sellerAvatar?: string
  conditionLevel?: number
  status: number
  reviewRemark?: string
  viewCount?: number
  createTime?: string
}

export interface ProductPublishPayload {
  title: string
  description?: string
  price: number
  originalPrice?: number
  image?: string
  categoryId?: number
  conditionLevel: number
}

/** 编辑商品为部分更新：所有字段可选，后端只更新非空字段 */
export interface ProductUpdatePayload {
  title?: string
  description?: string
  price?: number
  originalPrice?: number
  image?: string
  categoryId?: number
  conditionLevel?: number
}
