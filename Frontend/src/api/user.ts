import { request } from './request'
import type { LoginPayload, LoginVO, RegisterPayload, UserVO } from '@/types/user'

export const userApi = {
  login: (payload: LoginPayload) => request.post<LoginVO>('/user/login', payload),
  register: (payload: RegisterPayload) => request.post<void>('/user/register', payload),
  logout: () => request.post<void>('/user/logout'),
  getMe: () => request.get<UserVO>('/user/me'),
}
