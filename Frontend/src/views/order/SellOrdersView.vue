<script setup lang="ts">
import OrderListPanel from '@/components/OrderListPanel.vue'
import PageContainer from '@/components/PageContainer.vue'
</script>
<template>
  <PageContainer>
    <section><h1 class="page-title">我卖出的订单</h1><p class="page-subtitle">买家下单后商品会被锁定，确认交易后订单完成、商品标记为已售出。</p></section>
    <OrderListPanel mode="sell" />
  </PageContainer>
</template>
