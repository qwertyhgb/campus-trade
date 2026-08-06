export interface OrderVO {
  id: number
  orderNo: string
  productId: number
  productTitle: string
  productPrice: number
  productImage?: string
  buyerId: number
  buyerNickname?: string
  sellerId: number
  sellerNickname?: string
  status: number
  createTime?: string
}

export interface OrderPlacePayload {
  productId: number
}
