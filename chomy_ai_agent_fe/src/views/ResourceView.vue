<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import { session } from "../stores/session";
const route = useRoute();
// 此页面只承接已授权但尚未实现的菜单，守卫已按菜单 ID 和所属工作区校验。
const name = computed(
  () =>
    session.data?.permissions.find((p) => p.id === route.params.id)
      ?.permissionName || "工作空间",
);
</script>
<template>
  <main class="admin-page">
    <div class="eyebrow">工作空间</div>
    <h1>{{ name }}</h1>
    <p class="empty-state">此功能正在准备中。</p>
  </main>
</template>
