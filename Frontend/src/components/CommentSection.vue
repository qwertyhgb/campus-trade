<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '@/components/EmptyState.vue'
import { commentApi } from '@/api/comment'
import { useAuthStore } from '@/stores/auth'
import { formatDate, getErrorMessage } from '@/utils/format'
import type { CommentVO } from '@/types/comment'

const props = defineProps<{ productId: number }>()

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const pageSize = 10
const loading = ref(false)
const comments = ref<CommentVO[]>([])
const commentsTotal = ref(0)
const commentsPage = ref(1)
const replies = ref<Record<number, CommentVO[]>>({})
const content = ref('')
const replyingTo = ref<CommentVO | null>(null)
const submitting = ref(false)

function goLogin() {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}
function isMine(comment: CommentVO) {
  return authStore.user?.id === comment.userId
}
function startReply(comment: CommentVO) {
  if (!authStore.isLoggedIn) { goLogin(); return }
  replyingTo.value = comment
}

async function loadReplies(parentId: number) {
  try {
    const page = await commentApi.listReplies(parentId, 1, 10)
    replies.value[parentId] = page.records
  } catch {
    // 回复是次要内容，加载失败静默处理，不影响留言主列表。
  }
}

async function loadComments(pageNo = 1) {
  loading.value = true
  try {
    const page = await commentApi.listByProduct(props.productId, pageNo, pageSize)
    comments.value = page.records
    commentsTotal.value = page.total
    commentsPage.value = page.current
    // 顶级留言加载完成后，逐条并行拉取回复（数据量小，可接受）。
    await Promise.all(page.records.map((comment) => loadReplies(comment.id)))
  } catch (error) {
    comments.value = []
    commentsTotal.value = 0
    ElMessage.error(getErrorMessage(error, '留言加载失败'))
  } finally {
    loading.value = false
  }
}

function changePage(pageNo: number) { void loadComments(pageNo) }

async function submit() {
  if (!authStore.isLoggedIn) { goLogin(); return }
  const text = content.value.trim()
  if (!text) { ElMessage.warning('请输入留言内容'); return }
  submitting.value = true
  try {
    await commentApi.add({
      productId: props.productId,
      content: text,
      parentId: replyingTo.value?.id,
      replyToUserId: replyingTo.value?.userId,
    })
    ElMessage.success(replyingTo.value ? '回复成功' : '留言成功')
    content.value = ''
    if (replyingTo.value) {
      // 回复成功后只刷新该条留言的回复列表，不打断浏览位置。
      const parentId = replyingTo.value.id
      replyingTo.value = null
      await loadReplies(parentId)
    } else {
      // 留言按时间正序，新留言在最后一页，跳到最后一页查看。
      const lastPage = Math.max(1, Math.ceil((commentsTotal.value + 1) / pageSize))
      await loadComments(lastPage)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '发表失败'))
  } finally {
    submitting.value = false
  }
}

async function removeComment(comment: CommentVO) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这条留言吗？', '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return // 用户取消
  }
  try {
    await commentApi.remove(comment.id)
    ElMessage.success('删除成功')
    if (comment.parentId) {
      await loadReplies(comment.parentId)
    } else {
      await loadComments(commentsPage.value)
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '删除失败'))
  }
}

onMounted(() => { void loadComments(1) })
</script>
<template>
  <section class="comment-section section-card">
    <h2 class="comment-title">商品留言（{{ commentsTotal }}）</h2>
    <div class="comment-editor">
      <template v-if="authStore.isLoggedIn">
        <div v-if="replyingTo" class="reply-tip">正在回复 @{{ replyingTo.userNickname || '该用户' }}<el-button link type="primary" size="small" @click="replyingTo = null">取消回复</el-button></div>
        <el-input v-model="content" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="友善交流，留言最长 500 字" />
        <div class="editor-actions"><el-button type="primary" :loading="submitting" @click="submit">{{ replyingTo ? '发表回复' : '发表留言' }}</el-button></div>
      </template>
      <div v-else class="login-tip">登录后可以留言和回复，点击下方按钮去登录。<el-button type="primary" plain size="small" @click="goLogin">去登录</el-button></div>
    </div>
    <el-skeleton :loading="loading" animated :rows="3"><template #default>
      <div v-if="comments.length" class="comment-list">
        <div v-for="comment in comments" :key="comment.id" class="comment-item">
          <div class="comment-meta"><span class="comment-user">{{ comment.userNickname || '匿名用户' }}</span><span>{{ formatDate(comment.createTime) }}</span></div>
          <p class="comment-content">{{ comment.content }}</p>
          <div class="comment-actions">
            <el-button link type="primary" size="small" @click="startReply(comment)">回复</el-button>
            <el-button v-if="isMine(comment)" link type="danger" size="small" @click="removeComment(comment)">删除</el-button>
          </div>
          <div v-if="replies[comment.id]?.length" class="reply-list">
            <div v-for="reply in replies[comment.id]" :key="reply.id" class="reply-item">
              <div class="comment-meta"><span class="comment-user">{{ reply.userNickname || '匿名用户' }}</span><span v-if="reply.replyToNickname">回复 @{{ reply.replyToNickname }}</span><span>{{ formatDate(reply.createTime) }}</span></div>
              <p class="comment-content">{{ reply.content }}</p>
              <div class="comment-actions"><el-button v-if="isMine(reply)" link type="danger" size="small" @click="removeComment(reply)">删除</el-button></div>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="comment-empty"><EmptyState title="还没有留言" description="对这件商品感兴趣？发表第一条留言吧。" /></div>
    </template></el-skeleton>
    <el-pagination v-if="commentsTotal > pageSize" class="comment-pagination" background layout="prev, pager, next" :current-page="commentsPage" :page-size="pageSize" :total="commentsTotal" @current-change="changePage" />
  </section>
</template>
<style scoped>
.comment-section { display: grid; gap: 18px; padding: 26px; }
.comment-title { margin: 0; color: #172033; font-size: 20px; }
.comment-editor { display: grid; gap: 12px; }
.reply-tip { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px; background: #eff6ff; color: #2563eb; font-size: 13px; }
.editor-actions { display: flex; justify-content: flex-end; }
.login-tip { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 18px; border: 1px dashed #cbd5e1; border-radius: 10px; color: #64748b; }
.comment-list { display: grid; gap: 14px; }
.comment-item { display: grid; gap: 8px; padding: 16px 18px; border: 1px solid #e7edf5; border-radius: 12px; background: #fbfcfe; }
.comment-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; color: #94a3b8; font-size: 13px; }
.comment-user { color: #334155; font-weight: 600; }
.comment-content { margin: 0; color: #475569; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.comment-actions { display: flex; gap: 4px; }
.reply-list { display: grid; gap: 10px; margin-top: 4px; padding: 12px 14px; border-left: 2px solid #dbeafe; background: #f8fafc; border-radius: 0 10px 10px 0; }
.reply-item { display: grid; gap: 6px; }
.comment-empty { padding: 8px 0; }
.comment-pagination { justify-content: center; }
</style>
