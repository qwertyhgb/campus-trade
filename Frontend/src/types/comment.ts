export interface CommentVO {
  id: number
  productId: number
  userId: number
  userNickname?: string
  userAvatar?: string
  content: string
  parentId?: number
  replyToUserId?: number
  replyToNickname?: string
  createTime?: string
}

export interface CommentAddPayload {
  productId: number
  content: string
  parentId?: number
  replyToUserId?: number
}
