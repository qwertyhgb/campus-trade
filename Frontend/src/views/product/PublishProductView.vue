<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import ImageUploader from '@/components/ImageUploader.vue'
import PageContainer from '@/components/PageContainer.vue'
import { categoryApi } from '@/api/category'
import { productApi } from '@/api/product'
import { getErrorMessage } from '@/utils/format'
import type { CategoryVO } from '@/types/category'

const router = useRouter()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const categories = ref<CategoryVO[]>([])
const form = reactive({
  title: '',
  description: '',
  price: undefined as number | undefined,
  originalPrice: undefined as number | undefined,
  categoryId: undefined as number | undefined,
  conditionLevel: undefined as number | undefined,
  image: '',
})

const rules: FormRules<typeof form> = {
  title: [{ required: true, message: '请输入商品标题', trigger: 'blur' }, { max: 100, message: '标题不能超过 100 个字符', trigger: 'blur' }],
  description: [{ max: 1000, message: '描述不能超过 1000 个字符', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }, { type: 'number', min: 0.01, message: '价格必须大于 0', trigger: 'blur' }],
  originalPrice: [{ type: 'number', min: 0.01, message: '原价必须大于 0', trigger: 'blur' }],
  conditionLevel: [{ required: true, message: '请选择成色', trigger: 'change' }],
}

const conditionOptions = [
  { value: 0, label: '全新' },
  { value: 1, label: '几乎全新' },
  { value: 2, label: '轻微使用' },
  { value: 3, label: '明显使用' },
]

async function loadCategories() {
  try {
    categories.value = await categoryApi.list()
  } catch {
    ElMessage.warning('分类加载失败，可以稍后刷新重试')
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await productApi.publish({
      title: form.title,
      description: form.description || undefined,
      price: form.price as number,
      originalPrice: form.originalPrice || undefined,
      categoryId: form.categoryId || undefined,
      conditionLevel: form.conditionLevel as number,
      image: form.image || undefined,
    })
    ElMessage.success('发布成功，等待管理员审核')
    await router.push({ name: 'my-products' })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '发布失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(loadCategories)
</script>
<template>
  <PageContainer>
    <section><h1 class="page-title">发布商品</h1><p class="page-subtitle">填写商品信息，提交后等待管理员审核通过即可展示在商品广场。</p></section>
    <el-card class="section-card publish-card" shadow="never">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="商品标题" prop="title"><el-input v-model.trim="form.title" maxlength="100" show-word-limit placeholder="例如：九成新机械键盘，毕业出" /></el-form-item>
        <el-form-item label="商品描述" prop="description"><el-input v-model.trim="form.description" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="描述商品的使用情况、配件、出售原因等" /></el-form-item>
        <el-form-item label="售价" prop="price"><el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" placeholder="0.00" /><span class="field-hint">元（必填）</span></el-form-item>
        <el-form-item label="原价" prop="originalPrice"><el-input-number v-model="form.originalPrice" :min="0.01" :precision="2" :step="1" placeholder="0.00" /><span class="field-hint">元（选填）</span></el-form-item>
        <el-form-item label="分类" prop="categoryId"><el-select v-model="form.categoryId" clearable placeholder="选择分类" style="width: 240px"><el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item>
        <el-form-item label="成色" prop="conditionLevel"><el-select v-model="form.conditionLevel" placeholder="选择成色" style="width: 240px"><el-option v-for="option in conditionOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
        <el-form-item label="商品图片" prop="image"><ImageUploader v-model="form.image" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="submitting" @click="submit">发布商品</el-button><el-button @click="$router.back()">返回</el-button></el-form-item>
      </el-form>
    </el-card>
  </PageContainer>
</template>
<style scoped>
.publish-card { padding: 10px 16px 4px; }
.field-hint { margin-left: 10px; color: #94a3b8; font-size: 13px; }
</style>
