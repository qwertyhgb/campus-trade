import 'element-plus/dist/index.css'
import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

import App from './App.vue'
import router from './router'
import { setupUnauthorizedHandler } from './api/request'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)

const authStore = useAuthStore(pinia)
setupUnauthorizedHandler(() => {
  authStore.clearSession()
  const redirect = router.currentRoute.value.fullPath
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect } })
  }
})

app.mount('#app')
