<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import ImageUploader from '@/components/ImageUploader.vue'
import PageContainer from '@/components/PageContainer.vue'
import { categoryApi } from '@/api/category'
import { productApi } from '@/api/product'
import { getStatusText, productStatusText } from '@/constants/status'
import { formatPrice, getErrorMessage } from '@/utils/format'
import type { CategoryVO } from '@/types/category'
import type { ProductVO } from '@/types/product'

const route = useRoute()
const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(true)
const submitting = ref(false)
const loadFailed = ref(false)
const product = ref<ProductVO>()
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

async function loadProduct() {
  loading.value = true
  loadFailed.value = false
  try {
    // 编辑页必须走私有详情接口，公开详情不含审核备注等私有字段
    const detail = await productApi.getMyById(String(route.params.id))
    product.value = detail
    form.title = detail.title
    form.description = detail.description ?? ''
    form.price = detail.price
    form.originalPrice = detail.originalPrice
    form.categoryId = detail.categoryId
    form.conditionLevel = detail.conditionLevel
    form.image = detail.image ?? ''
  } catch (error) {
    loadFailed.value = true
    ElMessage.error(getErrorMessage(error, '商品信息加载失败'))
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    categories.value = await categoryApi.list()
  } catch {
    // 分类加载失败不阻塞编辑流程，仅影响下拉选项。
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await productApi.update(product.value!.id, {
      title: form.title,
      description: form.description || undefined,
      price: form.price,
      originalPrice: form.originalPrice || undefined,
      categoryId: form.categoryId || undefined,
      conditionLevel: form.conditionLevel,
      image: form.image || undefined,
    })
    ElMessage.success('保存成功')
    await router.push({ name: 'my-products' })
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存失败'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => { void loadProduct(); void loadCategories() })
</script>
<template>
  <PageContainer>
    <section><h1 class="page-title">编辑商品</h1><p class="page-subtitle" v-if="product">当前状态：{{ getStatusText(productStatusText, product.status) }} · 售价 {{ formatPrice(product.price) }}</p></section>
    <el-skeleton :loading="loading" animated :rows="6"><template #default>
      <div v-if="!loadFailed && product" class="section-card edit-card">
        <p v-if="product.reviewRemark" class="review-remark">审核备注：{{ product.reviewRemark }}（修改后可重新提交审核）</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
          <el-form-item label="商品标题" prop="title"><el-input v-model.trim="form.title" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="商品描述" prop="description"><el-input v-model.trim="form.description" type="textarea" :rows="5" maxlength="1000" show-word-limit /></el-form-item>
          <el-form-item label="售价" prop="price"><el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1" /><span class="field-hint">元</span></el-form-item>
          <el-form-item label="原价" prop="originalPrice"><el-input-number v-model="form.originalPrice" :min="0.01" :precision="2" :step="1" /><span class="field-hint">元（选填）</span></el-form-item>
          <el-form-item label="分类" prop="categoryId"><el-select v-model="form.categoryId" clearable placeholder="选择分类" style="width: 240px"><el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item>
          <el-form-item label="成色" prop="conditionLevel"><el-select v-model="form.conditionLevel" placeholder="选择成色" style="width: 240px"><el-option v-for="option in conditionOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select></el-form-item>
          <el-form-item label="商品图片" prop="image"><ImageUploader v-model="form.image" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="submitting" @click="submit">保存修改</el-button><el-button @click="$router.back()">返回</el-button></el-form-item>
        </el-form>
      </div>
      <div v-else-if="loadFailed" class="section-card"><EmptyState title="无法加载该商品" description="可能商品不存在，或你没有权限编辑它。" /></div>
    </template></el-skeleton>
  </PageContainer>
</template>
<style scoped>
.edit-card { padding: 18px 16px 4px; }
.review-remark { margin: 0 0 14px; padding: 10px 14px; border-radius: 8px; background: #fef2f2; color: #dc2626; font-size: 13px; }
.field-hint { margin-left: 10px; color: #94a3b8; font-size: 13px; }
</style>
