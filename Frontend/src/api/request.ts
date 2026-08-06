import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types/api'
import { STORAGE_KEYS } from '@/constants/storage'

export class ApiError extends Error {
  constructor(message: string, public readonly code?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

let unauthorizedHandler: (() => void) | undefined
export function setupUnauthorizedHandler(handler: () => void) { unauthorizedHandler = handler }

const http = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api', timeout: 15_000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(STORAGE_KEYS.token)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>
    if (typeof result?.code !== 'number') return response.data
    if (result.code !== 200) {
      if (result.code === 401) unauthorizedHandler?.()
      return Promise.reject(new ApiError(result.message || '业务处理失败', result.code))
    }
    return result.data
  },
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status
      const result = error.response?.data as Partial<ApiResult<unknown>> | undefined
      if (status === 401 || result?.code === 401) unauthorizedHandler?.()
      return Promise.reject(new ApiError(result?.message || error.message || '网络请求失败', result?.code ?? status))
    }
    return Promise.reject(error)
  },
)

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig) { return http.get(url, config) as unknown as Promise<T> },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) { return http.post(url, data, config) as unknown as Promise<T> },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) { return http.put(url, data, config) as unknown as Promise<T> },
  delete<T>(url: string, config?: AxiosRequestConfig) { return http.delete(url, config) as unknown as Promise<T> },
}
