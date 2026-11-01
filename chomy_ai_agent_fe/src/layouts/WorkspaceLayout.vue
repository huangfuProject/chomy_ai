<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import {
  PanelLeft,
  SquarePen,
  Bot,
  Users,
  ChevronUp,
  LogOut,
  ArrowLeftRight,
  X,
  LayoutGrid,
} from "lucide-vue-next";
import BrandMark from "../components/BrandMark.vue";
import { clearSession, session } from "../stores/session";
import { logout } from "../api/auth";
import { areaMenus, hasArea, menuTarget } from "../utils/permissions";
import type { Area } from "../types/api";

const route = useRoute(),
  router = useRouter();
const mobileOpen = ref(false),
  accountOpen = ref(false),
  signingOut = ref(false),
  error = ref("");
const area = computed<Area>(() =>
  route.meta.area === "backend" ? "backend" : "frontend",
);
const permissions = computed(() => session.data?.permissions ?? []);
const menus = computed(() => areaMenus(permissions.value, area.value));
const user = computed(() => session.data?.user);
const otherArea = computed<Area>(() =>
  area.value === "frontend" ? "backend" : "frontend",
);
const canSwitch = computed(() => hasArea(permissions.value, otherArea.value));
const displayName = computed(
  () => user.value?.nickname || user.value?.username || "用户",
);
// 当前已在聊天页时触发清空草稿；跨页面时由路由重建聊天组件。
function startChat() {
  if (route.path === "/chat") window.dispatchEvent(new Event("new-chat"));
}
watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false;
    accountOpen.value = false;
    error.value = "";
  },
);
/** 过期时立即退出界面；避免用户长期停留在聊天页而不发请求导致显示过期权限。 */
const expiryTimer = window.setTimeout(
  () => {
    clearSession();
    void router.replace("/login?reason=expired");
  },
  Math.min(Math.max(0, session.expiresAt - Date.now()), 2_147_483_647),
);
onBeforeUnmount(() => window.clearTimeout(expiryTimer));
/** 撤销成功才清理会话；失败展示重试提示，避免声称服务端已退出。 */
async function signOut() {
  if (signingOut.value) return;
  signingOut.value = true;
  error.value = "";
  try {
    await logout();
    clearSession();
    await router.replace("/login");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "退出失败，请重试";
  } finally {
    signingOut.value = false;
  }
}
</script>

<template>
  <div
    class="workspace"
    @keydown.esc="
      accountOpen = false;
      mobileOpen = false;
    "
  >
    <button
      v-if="mobileOpen"
      class="sidebar-overlay"
      aria-label="关闭导航"
      @click="mobileOpen = false"
    />
    <aside class="sidebar" :class="{ 'is-open': mobileOpen }">
      <div class="sidebar-top">
        <RouterLink :to="area === 'frontend' ? '/chat' : '/admin'" class="brand"
          ><BrandMark :size="27" /> Chomy <span>AI</span></RouterLink
        ><button
          class="icon-button mobile-close"
          aria-label="关闭导航"
          @click="mobileOpen = false"
        >
          <X :size="19" /></button
        ><PanelLeft class="desktop-panel" :size="18" />
      </div>
      <RouterLink
        v-if="area === 'frontend'"
        to="/chat"
        class="new-chat"
        @click="startChat"
        ><SquarePen :size="18" />开启新对话<span>＋</span></RouterLink
      >
      <div class="nav-caption">
        {{ area === "frontend" ? "工作空间" : "管理工作空间" }}
      </div>
      <nav
        class="workspace-nav"
        :aria-label="area === 'frontend' ? '用户前台菜单' : '用户后台菜单'"
      >
        <RouterLink
          v-for="menu in menus"
          :key="menu.id"
          :to="menuTarget(menu, area)"
          ><Bot
            v-if="menu.permissionCode === 'agent:manage'"
            :size="18"
          /><Users
            v-else-if="menu.permissionCode === 'user:manage'"
            :size="18"
          /><LayoutGrid v-else :size="18" />{{
            menu.permissionName
          }}</RouterLink
        >
      </nav>
      <div class="sidebar-empty">
        <span class="tiny-dot" />{{
          area === "frontend"
            ? "给每个想法，一个开始。"
            : "管理有序，协作从容。"
        }}
      </div>
      <div class="account-section">
        <p v-if="error" role="alert" class="error-message">{{ error }}</p>
        <div v-if="accountOpen" class="account-popover">
          <div class="account-caption">{{ user?.username }}</div>
          <button
            v-if="canSwitch"
            @click="router.push(otherArea === 'backend' ? '/admin' : '/chat')"
          >
            <ArrowLeftRight :size="17" />{{
              otherArea === "backend" ? "管理后端" : "跳转到前台"
            }}</button
          ><button :disabled="signingOut" @click="signOut">
            <LogOut :size="17" />{{ signingOut ? "正在退出…" : "退出登录" }}
          </button>
        </div>
        <button
          class="account-trigger"
          :aria-expanded="accountOpen"
          @click="accountOpen = !accountOpen"
        >
          <span class="avatar">{{ displayName.slice(0, 1).toUpperCase() }}</span
          ><span class="account-info"
            ><strong>{{ displayName }}</strong
            ><small>{{
              area === "frontend" ? "个人工作空间" : "后台管理"
            }}</small></span
          ><ChevronUp :size="16" :class="{ rotated: accountOpen }" />
        </button>
      </div>
    </aside>
    <div class="workspace-body">
      <header class="workspace-header">
        <div class="workspace-title">
          <button
            class="icon-button mobile-menu"
            aria-label="打开导航"
            @click="mobileOpen = true"
          >
            <PanelLeft :size="21" /></button
          ><span>{{ area === "frontend" ? "Chomy AI" : "管理后台" }}</span
          ><small>{{
            area === "frontend" ? "你的智能伙伴" : "工作空间"
          }}</small>
        </div>
        <span class="workspace-status"
          ><span class="tiny-dot" />{{
            area === "frontend" ? "灵感，从这里开始" : "用户后台"
          }}</span
        >
      </header>
      <RouterView :key="route.path" />
    </div>
  </div>
</template>
