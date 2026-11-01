<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { ArrowUpRight, LayoutGrid } from "lucide-vue-next";
import { session } from "../stores/session";
import { areaMenus, menuTarget } from "../utils/permissions";
const menus = computed(() =>
  areaMenus(session.data?.permissions ?? [], "backend"),
);
</script>
<template>
  <main class="admin-page">
    <div class="eyebrow">管理工作空间</div>
    <h1>让每一份协作井然有序。</h1>
    <p class="muted">从下方菜单开始，管理你的工作空间。</p>
    <div class="admin-cards">
      <RouterLink
        v-for="menu in menus"
        :key="menu.id"
        :to="menuTarget(menu, 'backend')"
        ><LayoutGrid :size="23" /><strong>{{ menu.permissionName }}</strong
        ><ArrowUpRight :size="19"
      /></RouterLink>
    </div>
    <p v-if="!menus.length" class="empty-state">
      暂无可访问的后台菜单，请联系管理员分配菜单权限。
    </p>
  </main>
</template>
