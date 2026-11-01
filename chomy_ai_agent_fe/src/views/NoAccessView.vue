<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { logout } from "../api/auth";
import { clearSession } from "../stores/session";

const router = useRouter(),
  busy = ref(false),
  error = ref("");
/** 无权限仍允许退出当前会话；成功撤销后才返回登录页。 */
async function signOut() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await logout();
    clearSession();
    await router.replace("/login");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "退出失败，请重试";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <!-- 无前后台目录授权时不挂载工作空间和菜单，保持空白页面与明确提示。 -->
  <main class="no-access">
    <p role="status">无任何权限，请联系管理员。</p>
    <button class="text-button" :disabled="busy" @click="signOut">
      {{ busy ? "正在退出…" : "退出登录" }}
    </button>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
  </main>
</template>
