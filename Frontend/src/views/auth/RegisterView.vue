<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { userApi } from '@/api/user'
import { getErrorMessage } from '@/utils/format'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '', phone: '' })
const rules: FormRules<typeof form> = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }, { min: 3, max: 20, message: '用户名长度为 3-20 位', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, max: 20, message: '密码长度为 6-20 位', trigger: 'blur' }],
  nickname: [{ max: 20, message: '昵称不能超过 20 个字符', trigger: 'blur' }],
}
async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try { await userApi.register(form); ElMessage.success('注册成功，请登录'); await router.replace({ name: 'login' }) }
  catch (error) { ElMessage.error(getErrorMessage(error, '注册失败')) }
  finally { loading.value = false }
}
</script>
<template><section class="auth-wrap"><el-card class="auth-card" shadow="never"><h1>注册</h1><p>创建本地学习环境的测试账号。</p><el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit"><el-form-item label="用户名" prop="username"><el-input v-model.trim="form.username" /></el-form-item><el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" show-password /></el-form-item><el-form-item label="昵称（可选）" prop="nickname"><el-input v-model.trim="form.nickname" /></el-form-item><el-form-item label="手机号（可选）" prop="phone"><el-input v-model.trim="form.phone" /></el-form-item><el-button class="submit-button" type="primary" :loading="loading" native-type="submit">注册</el-button></el-form><p class="auth-footer">已有账号？<RouterLink :to="{ name: 'login' }">去登录</RouterLink></p></el-card></section></template>
<style scoped>.auth-wrap { display: grid; min-height: 560px; place-items: center; }.auth-card { width: min(100%, 420px); padding: 8px; }.auth-card h1 { margin: 0; font-size: 28px; }.auth-card > p:first-of-type { margin: 10px 0 24px; color: #64748b; }.submit-button { width: 100%; }.auth-footer { margin: 20px 0 0; color: #64748b; text-align: center; }.auth-footer a { color: #2563eb; }</style>
