import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('@/layouts/PublicLayout.vue'),
      children: [
        { path: '', name: 'home', component: () => import('@/views/home/HomeView.vue') },
        { path: 'login', name: 'login', meta: { guestOnly: true }, component: () => import('@/views/auth/LoginView.vue') },
        { path: 'register', name: 'register', meta: { guestOnly: true }, component: () => import('@/views/auth/RegisterView.vue') },
        { path: 'products', name: 'product-list', component: () => import('@/views/product/ProductListView.vue') },
        // 静态路径（publish/mine）需在动态 /products/:id 之前声明，避免被详情页误伤
        { path: 'products/publish', name: 'product-publish', meta: { requiresAuth: true, label: '发布商品' }, component: () => import('@/views/product/PublishProductView.vue') },
        { path: 'products/mine', name: 'my-products', meta: { requiresAuth: true, label: '我的商品' }, component: () => import('@/views/product/MyProductsView.vue') },
        { path: 'products/mine/:id/edit', name: 'product-edit', meta: { requiresAuth: true, label: '编辑商品' }, component: () => import('@/views/product/ProductEditView.vue') },
        { path: 'products/:id', name: 'product-detail', component: () => import('@/views/product/ProductDetailView.vue') },
        { path: 'activities', name: 'activity-list', component: () => import('@/views/activity/ActivityListView.vue') },
        { path: 'activities/:id', name: 'activity-detail', component: () => import('@/views/activity/ActivityDetailView.vue') },
        { path: 'favorites', name: 'favorites', meta: { requiresAuth: true, label: '我的收藏' }, component: () => import('@/views/favorite/FavoritesView.vue') },
        { path: 'orders/buy', name: 'buy-orders', meta: { requiresAuth: true, label: '我买到的订单' }, component: () => import('@/views/order/BuyOrdersView.vue') },
        { path: 'orders/sell', name: 'sell-orders', meta: { requiresAuth: true, label: '我卖出的订单' }, component: () => import('@/views/order/SellOrdersView.vue') },
        { path: 'profile', name: 'profile', meta: { requiresAuth: true, label: '个人中心' }, component: () => import('@/views/common/FeaturePlaceholderView.vue') },
        { path: 'reservations', name: 'reservations', meta: { requiresAuth: true, label: '我的预约' }, component: () => import('@/views/common/FeaturePlaceholderView.vue') },
        { path: 'admin', name: 'admin', meta: { requiresAuth: true, roles: ['ADMIN'], label: '管理后台' }, component: () => import('@/views/common/FeaturePlaceholderView.vue') },
        { path: '403', name: 'forbidden', meta: { label: '无权限访问' }, component: () => import('@/views/common/FeaturePlaceholderView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/common/NotFoundView.vue') },
  ],
})

router.beforeEach((to) => {
  const authStore = useAuthStore()
  if (to.meta.guestOnly && authStore.isLoggedIn) return { name: 'home' }
  if (to.meta.requiresAuth && !authStore.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  const requiredRoles = to.meta.roles as string[] | undefined
  if (requiredRoles?.length && !authStore.hasAnyRole(requiredRoles)) return { name: 'forbidden' }
  return true
})

export default router
