<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
async function handleUserCommand(command: string) {
  if (command === 'logout') {
    try { await userApi.logout() } catch { /* Token 失效时仍需清理本地状态。 */ }
    finally { authStore.clearSession(); ElMessage.success('已退出登录'); await router.push({ name: 'home' }) }
    return
  }
  const routeMap: Record<string, string> = {
    publish: 'product-publish',
    favorites: 'favorites',
    'my-products': 'my-products',
    'buy-orders': 'buy-orders',
    'sell-orders': 'sell-orders',
    profile: 'profile',
  }
  const name = routeMap[command]
  if (name) await router.push({ name })
}
</script>
<template>
  <header class="site-header"><div class="header-inner">
    <RouterLink class="brand" :to="{ name: 'home' }"><span class="brand-mark">CT</span><span>校园交易与活动预约</span></RouterLink>
    <nav class="main-nav" aria-label="主导航"><RouterLink :to="{ name: 'home' }">首页</RouterLink><RouterLink :to="{ name: 'product-list' }">商品</RouterLink><RouterLink :to="{ name: 'activity-list' }">活动</RouterLink></nav>
    <div class="header-actions">
      <template v-if="authStore.isLoggedIn"><el-dropdown @command="handleUserCommand"><span class="user-trigger">{{ authStore.displayName }}</span><template #dropdown><el-dropdown-menu>
        <el-dropdown-item command="publish">发布商品</el-dropdown-item>
        <el-dropdown-item command="my-products">我的商品</el-dropdown-item>
        <el-dropdown-item command="favorites">我的收藏</el-dropdown-item>
        <el-dropdown-item command="buy-orders">我买到的</el-dropdown-item>
        <el-dropdown-item command="sell-orders">我卖出的</el-dropdown-item>
        <el-dropdown-item command="profile">个人中心</el-dropdown-item>
        <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
      </el-dropdown-menu></template></el-dropdown></template>
      <template v-else><RouterLink :to="{ name: 'login' }">登录</RouterLink><el-button type="primary" size="small" @click="router.push({ name: 'register' })">注册</el-button></template>
    </div>
  </div></header>
</template>
<style scoped>
.site-header { border-bottom: 1px solid #e8edf4; background: rgb(255 255 255 / 92%); backdrop-filter: blur(12px); }
.header-inner { display: flex; width: min(1180px, calc(100% - 32px)); min-height: 64px; align-items: center; gap: 32px; margin: 0 auto; }
.brand { display: inline-flex; align-items: center; gap: 10px; color: #172033; font-weight: 700; white-space: nowrap; }.brand-mark { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: linear-gradient(135deg, #2563eb, #0ea5a4); color: #fff; font-size: 12px; }
.main-nav { display: flex; align-self: stretch; align-items: center; gap: 20px; color: #64748b; }.main-nav a.router-link-active { color: #2563eb; font-weight: 600; }.header-actions { display: flex; align-items: center; gap: 14px; margin-left: auto; color: #475569; }.user-trigger { cursor: pointer; color: #334155; }
@media (max-width: 700px) { .header-inner { width: min(100% - 24px, 1180px); gap: 14px; }.main-nav { gap: 12px; font-size: 14px; }.brand > span:last-child { display: none; } }
</style>
