<script setup lang="ts">
withDefaults(defineProps<{ title?: string; description?: string }>(), { title: '暂时没有数据', description: '可以稍后刷新再试。' })
</script>
<template><div class="empty-state"><strong>{{ title }}</strong><span>{{ description }}</span><slot /></div></template>
<style scoped>.empty-state { display: grid; min-height: 180px; place-content: center; gap: 8px; padding: 24px; color: #64748b; text-align: center; } .empty-state strong { color: #334155; }</style>
