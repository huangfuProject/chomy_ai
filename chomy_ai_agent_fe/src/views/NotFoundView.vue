<script setup lang="ts">
import { RouterLink } from "vue-router";
import { session } from "../stores/session";
import { landingPath } from "../utils/permissions";
</script>
<template>
  <main class="no-access">
    <h1>页面不存在</h1>
    <RouterLink
      class="text-button"
      :to="landingPath(session.data?.permissions ?? [])"
      >返回工作空间</RouterLink
    >
  </main>
</template>
