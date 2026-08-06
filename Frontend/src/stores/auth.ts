import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { userApi } from '@/api/user'
import { STORAGE_KEYS } from '@/constants/storage'
import type { LoginPayload, LoginVO, RoleCode, UserVO } from '@/types/user'

function readUser() {
  try {
    const value = localStorage.getItem(STORAGE_KEYS.user)
    return value ? (JSON.parse(value) as UserVO) : null
  } catch { return null }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(STORAGE_KEYS.token) || '')
  const user = ref<UserVO | null>(readUser())
  const isLoggedIn = computed(() => Boolean(token.value))
  const displayName = computed(() => user.value?.nickname || user.value?.username || '用户')

  function setSession(loginVO: LoginVO) {
    token.value = loginVO.token
    user.value = loginVO.userVO
    localStorage.setItem(STORAGE_KEYS.token, loginVO.token)
    localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(loginVO.userVO))
  }
  async function login(payload: LoginPayload) { const loginVO = await userApi.login(payload); setSession(loginVO); return loginVO }
  function updateUser(nextUser: UserVO) { user.value = nextUser; localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(nextUser)) }
  function clearSession() { token.value = ''; user.value = null; localStorage.removeItem(STORAGE_KEYS.token); localStorage.removeItem(STORAGE_KEYS.user) }
  function hasAnyRole(roles: string[]) { const roleCodes = user.value?.roleCodes ?? []; return roles.some((role) => roleCodes.includes(role as RoleCode)) }

  return { token, user, isLoggedIn, displayName, login, setSession, updateUser, clearSession, hasAnyRole }
})
