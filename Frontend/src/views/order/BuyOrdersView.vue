<script setup lang="ts">
import OrderListPanel from '@/components/OrderListPanel.vue'
import PageContainer from '@/components/PageContainer.vue'
</script>
<template>
  <PageContainer>
    <section><h1 class="page-title">我买到的订单</h1><p class="page-subtitle">待确认订单可取消；卖家确认后订单完成。</p></section>
    <OrderListPanel mode="buy" />
  </PageContainer>
</template>
