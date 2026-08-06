import dayjs from 'dayjs'

export function formatDate(value?: string, pattern = 'YYYY-MM-DD HH:mm') { return value ? dayjs(value).format(pattern) : '-' }
export function formatPrice(value?: number) { return typeof value === 'number' ? `¥${value.toFixed(2)}` : '-' }
export function getErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') { return error instanceof Error && error.message ? error.message : fallback }
