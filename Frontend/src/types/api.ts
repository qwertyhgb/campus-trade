export interface ApiResult<T> {
  code: number
  message: string
  data?: T
}

export interface PageResult<T> {
  records: T[]
  total: number
  pages: number
  current: number
  size: number
}
