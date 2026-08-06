export interface ActivityQuery {
  pageNo?: number
  pageSize?: number
  keyword?: string
  categoryId?: number
  status?: number
  startTimeFrom?: string
  startTimeTo?: string
}

export interface ActivityListItemVO {
  id: number
  title: string
  coverImage?: string
  categoryId?: number
  categoryName?: string
  location: string
  startTime: string
  endTime: string
  currentCount: number
  maxCount: number
  status: number
  organizerId: number
  organizerNickname?: string
  createTime?: string
}

export interface ActivityDetailVO extends ActivityListItemVO {
  description: string
  enrollStartTime: string
  enrollEndTime: string
  waitingListCount?: number
  rejectReason?: string
}
