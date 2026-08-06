export type RoleCode = 'USER' | 'ORGANIZER' | 'AUDITOR' | 'ADMIN'

export interface UserVO {
  id: number
  username: string
  nickname?: string
  phone?: string
  avatar?: string
  status: number
  /** 后端当前兼容字段；多角色菜单完成前请确认是否会返回 roleCodes。 */
  role?: number
  roleCodes?: RoleCode[]
  createTime?: string
}

export interface LoginPayload { username: string; password: string }
export interface RegisterPayload { username: string; password: string; nickname?: string; phone?: string }
export interface LoginVO { token: string; userVO: UserVO }
