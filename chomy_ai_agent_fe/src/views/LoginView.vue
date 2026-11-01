<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowUpRight, Eye, EyeOff, ArrowRight } from "lucide-vue-next";
import BrandMark from "../components/BrandMark.vue";
import { login } from "../api/auth";
import { setSession, clearSession } from "../stores/session";
import { landingPath } from "../utils/permissions";

const router = useRouter(),
  route = useRoute();
const username = ref(""),
  password = ref(""),
  showPassword = ref(false),
  busy = ref(false);
const error = ref(
  route.query.reason === "expired"
    ? "登录已过期，请重新登录"
    : route.query.reason === "verify"
      ? "无法验证登录状态，请重新登录"
      : "",
);
/** 一次提交完整账号密码；重复提交、空白用户名、BCrypt 字节上限均在发送前处理。 */
async function submit() {
  if (busy.value) return;
  error.value = "";
  if (!username.value.trim() || !password.value.trim()) {
    error.value = "请输入用户名和密码";
    return;
  }
  if (new TextEncoder().encode(password.value).length > 72) {
    error.value = "密码最多支持 72 个 UTF-8 字节";
    return;
  }
  busy.value = true;
  try {
    // 重新认证失败时不保留旧账户权限，避免账户切换后沿用上次会话。
    clearSession();
    const result = await login(username.value.trim(), password.value);
    setSession(result);
    password.value = "";
    await router.replace(landingPath(result.permissions));
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "登录失败，请稍后重试";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <header class="login-header">
      <a href="/login" class="brand"
        ><BrandMark :size="28" /> Chomy <span>AI</span></a
      ><span class="header-note"
        >你的想法，值得更多可能 <ArrowUpRight :size="15"
      /></span>
    </header>
    <main class="login-main">
      <div class="login-symbol"><BrandMark :size="48" /></div>
      <h1>欢迎回来</h1>
      <p class="login-subtitle">登录 Chomy AI，开启你的下一段灵感。</p>
      <form class="login-form" @submit.prevent="submit">
        <label for="username">用户名</label
        ><input
          id="username"
          v-model="username"
          name="username"
          autocomplete="username"
          placeholder="请输入用户名"
          maxlength="64"
          required
          :disabled="busy"
        />
        <label for="password">密码</label>
        <div class="password-input">
          <input
            id="password"
            v-model="password"
            name="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            placeholder="请输入密码"
            maxlength="128"
            required
            :disabled="busy"
          /><button
            type="button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            :aria-pressed="showPassword"
            @click="showPassword = !showPassword"
          >
            <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
          </button>
        </div>
        <p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <button class="primary login-submit" :disabled="busy">
          {{ busy ? "正在登录…" : "登录"
          }}<ArrowRight v-if="!busy" :size="18" />
        </button>
      </form>
      <p class="account-note">
        还没有账号或无法登录？<br /><span
          >请联系管理员开通账号与访问权限。</span
        >
      </p>
    </main>
    <footer class="login-footer">
      <span>Chomy AI · 让灵感自然发生</span
      ><span>与你一起，把想法变成可能。</span>
    </footer>
  </div>
</template>
