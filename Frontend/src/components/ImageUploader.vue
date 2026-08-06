<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadApi } from '@/api/upload'
import { getErrorMessage } from '@/utils/format'

const props = withDefaults(defineProps<{ modelValue?: string; maxSizeMB?: number }>(), { modelValue: '', maxSizeMB: 5 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const inputRef = ref<HTMLInputElement>()
const uploading = ref(false)

const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp']

function pickFile() {
  if (uploading.value) return
  inputRef.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一个文件
  if (!file) return

  // 前端预校验：格式与大小，与后端 /upload/image 的限制保持一致
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.includes(extension)) {
    ElMessage.warning('只支持 jpg/jpeg/png/gif/webp 格式')
    return
  }
  const maxBytes = props.maxSizeMB * 1024 * 1024
  if (file.size > maxBytes) {
    ElMessage.warning(`图片大小不能超过 ${props.maxSizeMB}MB`)
    return
  }

  uploading.value = true
  try {
    const url = await uploadApi.uploadImage(file)
    emit('update:modelValue', url)
    ElMessage.success('图片上传成功')
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '图片上传失败'))
  } finally {
    uploading.value = false
  }
}

function removeImage() {
  emit('update:modelValue', '')
}
</script>
<template>
  <div class="image-uploader">
    <input ref="inputRef" type="file" accept=".jpg,.jpeg,.png,.gif,.webp" hidden @change="handleFileChange" />
    <div v-if="!modelValue" class="upload-placeholder" role="button" tabindex="0" @click="pickFile" @keydown.enter="pickFile">
      <span v-if="uploading" class="el-icon is-loading"><svg viewBox="0 0 1024 1024" width="18" height="18"><path fill="currentColor" d="M512 64a32 32 0 0 1 32 32v192a32 32 0 0 1-64 0V96a32 32 0 0 1 32-32zm0 640a32 32 0 0 1 32 32v192a32 32 0 0 1-64 0V736a32 32 0 0 1 32-32zm448-192a32 32 0 0 1-32 32H736a32 32 0 0 1 0-64h192a32 32 0 0 1 32 32zm-640 0a32 32 0 0 1-32 32H96a32 32 0 0 1 0-64h192a32 32 0 0 1 32 32zM195.2 195.2a32 32 0 0 1 45.248 0L376.32 331.008a32 32 0 0 1-45.248 45.248L195.2 240.448a32 32 0 0 1 0-45.248zm452.544 452.544a32 32 0 0 1 45.248 0L828.8 783.552a32 32 0 0 1-45.248 45.248L647.744 692.992a32 32 0 0 1 0-45.248z"/></svg></span>
      <template v-else><span class="upload-plus">+</span><span>上传商品图片</span><small>支持 jpg/png/gif/webp，不超过 {{ maxSizeMB }}MB</small></template>
    </div>
    <div v-else class="upload-preview">
      <img :src="modelValue" alt="商品图片预览" />
      <div class="preview-actions">
        <el-button size="small" :loading="uploading" @click="pickFile">重新上传</el-button>
        <el-button size="small" type="danger" plain @click="removeImage">移除</el-button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.image-uploader { display: inline-block; }
.upload-placeholder { display: grid; width: 220px; height: 180px; place-content: center; gap: 6px; border: 1.5px dashed #cbd5e1; border-radius: 12px; background: #f8fafc; color: #64748b; text-align: center; cursor: pointer; transition: border-color .2s, background .2s; }
.upload-placeholder:hover { border-color: #2563eb; background: #eff6ff; }
.upload-plus { color: #2563eb; font-size: 26px; line-height: 1; }
.upload-placeholder small { color: #94a3b8; font-size: 12px; }
.upload-preview { position: relative; display: inline-block; border-radius: 12px; overflow: hidden; }
.upload-preview img { display: block; width: 220px; height: 180px; object-fit: cover; }
.preview-actions { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 10px; background: rgb(15 23 42 / 45%); opacity: 0; transition: opacity .2s; }
.upload-preview:hover .preview-actions { opacity: 1; }
</style>
