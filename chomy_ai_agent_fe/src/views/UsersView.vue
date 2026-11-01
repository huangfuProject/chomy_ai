<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { Search, ChevronLeft, ChevronRight, X, Users } from "lucide-vue-next";
import { getUser, getUsers } from "../api/users";
import { session } from "../stores/session";
import { hasPermission } from "../utils/permissions";
import type { AdminUser } from "../types/api";

const rows = ref<AdminUser[]>([]),
  detail = ref<AdminUser | null>(null),
  username = ref("");
const current = ref(1),
  pages = ref(0),
  total = ref(0),
  busy = ref(false),
  error = ref(""),
  detailBusy = ref(false);
const permissions = computed(() => session.data?.permissions ?? []);
const canPage = computed(() =>
  hasPermission(permissions.value, "user:get:page"),
);
const canDetail = computed(() =>
  hasPermission(permissions.value, "user:get:detail"),
);
const detailDialog = ref<HTMLDialogElement | null>(null);
// 使用原生模态对话框锁定焦点，键盘和读屏不会误操作背后的用户列表。
watch(detail, async (value) => {
  await nextTick();
  if (value && !detailDialog.value?.open) detailDialog.value?.showModal();
  else if (!value && detailDialog.value?.open) detailDialog.value.close();
});
let generation = 0;
/** 请求代数避免较早响应覆盖新筛选结果；无分页权限时不会发出请求。 */
async function load(page = 1) {
  if (!canPage.value) return;
  const requestId = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await getUsers(page, username.value);
    if (requestId !== generation) return;
    rows.value = result.records;
    current.value = Number(result.current);
    pages.value = Number(result.pages);
    total.value = Number(result.total);
  } catch (cause) {
    if (requestId === generation) {
      rows.value = [];
      error.value = cause instanceof Error ? cause.message : "用户列表加载失败";
    }
  } finally {
    if (requestId === generation) busy.value = false;
  }
}
/** 详情操作只按独立权限显示，并保留服务端的最终 403 判断。 */
async function showDetail(id: string) {
  if (!canDetail.value || detailBusy.value) return;
  detailBusy.value = true;
  error.value = "";
  const requestId = generation;
  try {
    const result = await getUser(id);
    if (requestId === generation) detail.value = result;
  } catch (cause) {
    if (requestId === generation)
      error.value = cause instanceof Error ? cause.message : "详情加载失败";
  } finally {
    detailBusy.value = false;
  }
}
onMounted(() => {
  void load();
});
onBeforeUnmount(() => {
  generation++;
});
</script>
<template>
  <main class="admin-page users-page">
    <div class="eyebrow">用户后台 / 用户管理</div>
    <div class="page-heading">
      <div>
        <h1>用户管理</h1>
        <p class="muted">查看工作空间中的用户与账户状态。</p>
      </div>
      <span class="count-badge"><Users :size="16" />{{ total }} 位用户</span>
    </div>
    <p v-if="!canPage" class="empty-state">
      无用户分页查询权限，请联系管理员。
    </p>
    <template v-else
      ><form class="search-form" @submit.prevent="load(1)">
        <Search :size="18" /><input
          v-model="username"
          aria-label="搜索用户名"
          placeholder="按用户名搜索"
          maxlength="64"
        /><button class="secondary" :disabled="busy">搜索</button>
      </form>
      <p v-if="error" role="alert" class="error-message">
        {{ error
        }}<button class="text-button" @click="load(current)">重试</button>
      </p>
      <div class="table-container" :aria-busy="busy">
        <table>
          <thead>
            <tr>
              <th>用户</th>
              <th>手机号</th>
              <th>账户状态</th>
              <th>激活状态</th>
              <th v-if="canDetail">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in rows" :key="user.id">
              <td>
                <strong>{{ user.nickname }}</strong
                ><small>{{ user.username }}</small>
              </td>
              <td>{{ user.phone }}</td>
              <td>
                <span class="status-badge" :class="{ frozen: user.frozen }">{{
                  user.frozen ? "已冻结" : "正常"
                }}</span>
              </td>
              <td>{{ user.active ? "已激活" : "未激活" }}</td>
              <td v-if="canDetail">
                <button
                  class="text-button"
                  :disabled="detailBusy"
                  @click="showDetail(user.id)"
                >
                  查看详情
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="busy" class="empty-state" role="status">正在加载用户…</div>
        <div v-else-if="!rows.length && !error" class="empty-state">
          暂无符合条件的用户
        </div>
      </div>
      <div class="pagination">
        <span>共 {{ total }} 条 · 第 {{ current }} / {{ pages || 1 }} 页</span
        ><button
          class="icon-button"
          aria-label="上一页"
          :disabled="busy || current <= 1"
          @click="load(current - 1)"
        >
          <ChevronLeft :size="18" /></button
        ><button
          class="icon-button"
          aria-label="下一页"
          :disabled="busy || current >= pages"
          @click="load(current + 1)"
        >
          <ChevronRight :size="18" />
        </button></div
    ></template>
    <dialog
      ref="detailDialog"
      @close="detail = null"
      class="detail-dialog"
      aria-labelledby="detail-title"
      @keydown.esc.prevent="detail = null"
    >
      <template v-if="detail"
        ><div class="dialog-heading">
          <h2 id="detail-title">用户详情</h2>
          <button
            class="icon-button"
            aria-label="关闭详情"
            autofocus
            @click="detail = null"
          >
            <X :size="20" />
          </button>
        </div>
        <dl>
          <dt>用户名</dt>
          <dd>{{ detail.username }}</dd>
          <dt>昵称</dt>
          <dd>{{ detail.nickname }}</dd>
          <dt>手机号</dt>
          <dd>{{ detail.phone }}</dd>
          <dt>个性签名</dt>
          <dd>{{ detail.signature || "未设置" }}</dd>
          <dt>创建时间</dt>
          <dd>{{ detail.createdAt }}</dd>
        </dl></template
      >
    </dialog>
  </main>
</template>
