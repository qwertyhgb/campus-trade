<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppFooter from '@/components/AppFooter.vue'
import AppHeader from '@/components/AppHeader.vue'
</script>
<template><div class="app-shell"><AppHeader /><main class="app-main"><RouterView /></main><AppFooter /></div></template>
<style scoped>.app-shell { display: flex; min-height: 100vh; flex-direction: column; }.app-main { width: min(1180px, calc(100% - 32px)); flex: 1; margin: 0 auto; padding: 32px 0 48px; } @media (max-width: 640px) { .app-main { width: min(100% - 24px, 1180px); padding-top: 22px; } }</style>
