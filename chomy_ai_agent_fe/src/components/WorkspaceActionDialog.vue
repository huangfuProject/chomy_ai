<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { X, ArchiveRestore, Trash2 } from "lucide-vue-next";
import { workspace } from "../stores/workspace";

const router = useRouter(),
  route = useRoute();
const dialog = ref<HTMLDialogElement | null>(null),
  field = ref<HTMLInputElement | null>(null);
const action = ref(""),
  kind = ref<"chat" | "project">("chat"),
  id = ref("");
const name = ref(""),
  description = ref(""),
  targetProject = ref(""),
  error = ref("");
const archivedChats = computed(() =>
  workspace.state.chats.filter((c) => !!c.archivedAt),
);
const title = computed(() =>
  action.value === "archives"
    ? "已归档聊天"
    : action.value === "share"
      ? kind.value === "chat"
        ? "分享聊天"
        : "分享项目"
      : action.value === "move"
        ? "移至项目"
        : action.value === "settings"
          ? "项目设置"
          : action.value === "delete"
            ? kind.value === "chat"
              ? "永久删除聊天？"
              : "删除项目？"
            : kind.value === "chat"
              ? "重命名聊天"
              : "重命名项目",
);
/** 集中管理原生模态框，避免每条聊天各自挂载多个弹窗和重复 ID。 */
async function open(
  nextAction: string,
  nextId = "",
  nextKind: "chat" | "project" = "chat",
) {
  action.value = nextAction;
  kind.value = nextKind;
  id.value = nextId;
  error.value = "";
  const chat = workspace.state.chats.find((c) => c.id === nextId),
    project = workspace.state.projects.find((p) => p.id === nextId);
  name.value =
    nextKind === "chat" ? (chat?.title ?? "") : (project?.name ?? "");
  description.value = project?.description ?? "";
  targetProject.value = chat?.projectId ?? "";
  if (nextAction === "archive") {
    try {
      workspace.archiveChat(nextId);
      if (route.params.chatId === nextId) await router.replace("/chat");
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : "归档失败";
    }
    if (!error.value) return;
  }
  if (!dialog.value?.open) dialog.value?.showModal();
  await nextTick();
  field.value?.focus();
}
/** 删除及移动均校验真实记录；删除当前页面的数据后回到安全的前台首页。 */
async function submit() {
  error.value = "";
  try {
    if (action.value === "rename") {
      if (kind.value === "chat") workspace.renameChat(id.value, name.value);
      else workspace.updateProject(id.value, name.value);
    } else if (action.value === "settings")
      workspace.updateProject(id.value, name.value, description.value);
    else if (action.value === "move")
      workspace.moveChat(id.value, targetProject.value || null);
    else if (action.value === "delete") {
      const activeChat = workspace.state.chats.find(
        (c) => c.id === route.params.chatId,
      );
      const leave =
        kind.value === "chat"
          ? route.params.chatId === id.value
          : route.params.projectId === id.value ||
            activeChat?.projectId === id.value;
      if (kind.value === "chat") workspace.deleteChat(id.value);
      else workspace.deleteProject(id.value);
      if (leave) await router.replace("/chat");
    }
    dialog.value?.close();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "操作失败，请重试";
  }
}
function restore(id: string) {
  workspace.archiveChat(id, false);
}
defineExpose({ open });
</script>
<template>
  <dialog
    ref="dialog"
    class="project-dialog resource-action-dialog"
    aria-labelledby="resource-action-title"
  >
    <form @submit.prevent="submit">
      <div class="dialog-heading">
        <h2 id="resource-action-title">{{ title }}</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭操作窗口"
          @click="dialog?.close()"
        >
          <X :size="20" />
        </button>
      </div>
      <template v-if="action === 'rename' || action === 'settings'"
        ><label for="resource-name">{{
          kind === "chat" ? "聊天名称" : "项目名称"
        }}</label
        ><input
          id="resource-name"
          ref="field"
          v-model="name"
          required
          :maxlength="kind === 'chat' ? 100 : 64" /><template
          v-if="action === 'settings'"
          ><label for="resource-description">项目介绍</label
          ><textarea
            id="resource-description"
            v-model="description"
            rows="4"
            maxlength="500"
          /></template
      ></template>
      <template v-else-if="action === 'move'"
        ><p class="muted">选择“{{ name }}”所属的项目。</p>
        <label for="destination-project">目标项目</label
        ><select id="destination-project" v-model="targetProject">
          <option value="">无项目（独立聊天）</option>
          <option
            v-for="project in workspace.listProjects()"
            :key="project.id"
            :value="project.id"
          >
            {{ project.name }}
          </option>
        </select>
        <p v-if="!workspace.state.projects.length" class="muted">
          暂无项目，可先从侧栏“项目”菜单创建。
        </p></template
      >
      <template v-else-if="action === 'delete'"
        ><p class="delete-explanation">
          {{
            kind === "chat"
              ? `确定永久删除“${name}”？聊天及其草稿将被移除，无法恢复。`
              : `确定删除“${name}”？项目及其中的全部聊天（含归档记录）将被永久删除，无法恢复。`
          }}
        </p></template
      >
      <template v-else-if="action === 'share'"
        ><p class="muted share-placeholder">
          分享功能即将开放，暂时无法创建分享链接。
        </p></template
      >
      <template v-else-if="action === 'archives'"
        ><p v-if="!archivedChats.length" class="muted share-placeholder">
          暂无已归档聊天。
        </p>
        <div v-else class="archived-list">
          <div
            v-for="chat in archivedChats"
            :key="chat.id"
            class="archived-row"
          >
            <span>{{ chat.title }}</span
            ><button
              type="button"
              class="icon-button"
              :aria-label="`恢复聊天 ${chat.title}`"
              @click="restore(chat.id)"
            >
              <ArchiveRestore :size="17" /></button
            ><button
              type="button"
              class="icon-button danger-action"
              :aria-label="`永久删除归档聊天 ${chat.title}`"
              @click="open('delete', chat.id)"
            >
              <Trash2 :size="17" />
            </button>
          </div></div
      ></template>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <div class="project-dialog-actions">
        <button type="button" class="text-button" @click="dialog?.close()">
          {{
            action === "share" || action === "archives" ? "关闭" : "取消"
          }}</button
        ><button
          v-if="!['share', 'archives', 'archive'].includes(action)"
          class="primary"
          :class="{ 'danger-button': action === 'delete' }"
          :disabled="
            (action === 'rename' || action === 'settings') && !name.trim()
          "
        >
          {{
            action === "delete"
              ? "永久删除"
              : action === "move"
                ? "移动"
                : "保存"
          }}
        </button>
      </div>
    </form>
  </dialog>
</template>
