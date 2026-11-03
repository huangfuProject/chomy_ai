<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { X, Pencil, Trash2, ArchiveRestore, Folder } from "lucide-vue-next";
import { workspace } from "../stores/workspace";
import InfiniteLoad from "./InfiniteLoad.vue";

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
  error = ref(""),
  busy = ref(false);
const archivedChats = computed(() => workspace.listArchivedChats());
const title = computed(() =>
  action.value === "archives"
    ? "已归档聊天"
    : action.value === "move"
      ? "移至项目"
      : action.value === "archive"
        ? "归档聊天"
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
/** 请求提交期间锁定当前弹窗，所有数据修改成功后才更新导航、归档列表及路由。 */
async function open(
  nextAction: string,
  nextId = "",
  nextKind: "chat" | "project" = "chat",
) {
  if (
    busy.value ||
    !["archives", "rename", "settings", "delete", "archive", "move"].includes(
      nextAction,
    )
  )
    return;
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
  if (!dialog.value?.open) dialog.value?.showModal();
  if (nextAction === "archives" && !workspace.state.archivedPage.initialized)
    void workspace.loadArchived();
  if (nextAction === "move" && !workspace.state.projectsPage.initialized)
    void workspace.loadProjects();
  await nextTick();
  field.value?.focus();
  // 归档是可恢复操作，点击后直接提交；失败时保留说明和重试按钮，不伪造成功。
  if (nextAction === "archive") await submit();
}
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    if (action.value === "rename") {
      if (kind.value === "chat")
        await workspace.renameChat(id.value, name.value);
      else await workspace.updateProject(id.value, name.value);
    } else if (action.value === "settings")
      await workspace.updateProject(id.value, name.value, description.value);
    else if (action.value === "archive") {
      await workspace.archiveChat(id.value);
      if (route.params.chatId === id.value) await router.replace("/chat");
    } else if (action.value === "move") {
      await workspace.moveChat(id.value, targetProject.value || null);
      // 项目聊天深层链接携带项目 ID；移动当前窗口后同步地址，保证刷新定位新的所属列表。
      if (route.params.chatId === id.value)
        await router.replace({
          path: route.path,
          query: targetProject.value ? { projectId: targetProject.value } : {},
        });
    } else if (action.value === "delete") {
      const activeChat = workspace.state.chats.find(
        (c) => c.id === route.params.chatId,
      );
      const leave =
        kind.value === "chat"
          ? route.params.chatId === id.value
          : route.params.projectId === id.value ||
            activeChat?.projectId === id.value;
      if (kind.value === "chat") await workspace.deleteChat(id.value);
      else await workspace.deleteProject(id.value);
      if (leave) await router.replace("/chat");
    }
    dialog.value?.close();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "操作失败，请重试";
  } finally {
    busy.value = false;
  }
}
/** 恢复有自己的权限码；失败后保留归档记录和错误提示，成功后滚动分页从第一页刷新。 */
async function restore(chatId: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await workspace.archiveChat(chatId, false);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "恢复失败，请重试";
  } finally {
    busy.value = false;
  }
}
function close() {
  if (!busy.value) dialog.value?.close();
}
defineExpose({ open });
</script>
<template>
  <dialog
    ref="dialog"
    class="project-dialog resource-action-dialog"
    aria-labelledby="resource-action-title"
    @cancel="busy && $event.preventDefault()"
    @close="action = ''"
  >
    <form @submit.prevent="submit">
      <div class="dialog-heading">
        <h2 id="resource-action-title">{{ title }}</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭操作窗口"
          :disabled="busy"
          @click="close"
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
          maxlength="128"
          :disabled="busy" /><template v-if="action === 'settings'"
          ><label for="resource-description">项目介绍</label
          ><textarea
            id="resource-description"
            v-model="description"
            rows="4"
            maxlength="2000"
            :disabled="busy"
          /></template
      ></template>
      <template v-else-if="action === 'move'"
        ><p class="muted move-description">选择“{{ name }}”所属的项目。</p>
        <div
          class="move-project-options"
          role="radiogroup"
          aria-label="目标项目"
          data-scroll-root
        >
          <label class="move-project-option"
            ><input
              v-model="targetProject"
              type="radio"
              name="target-project"
              value=""
              :disabled="busy"
            /><Folder :size="17" /><span>无项目（独立聊天）</span></label
          ><label
            v-for="project in workspace.listProjects()"
            :key="project.id"
            class="move-project-option"
            ><input
              v-model="targetProject"
              type="radio"
              name="target-project"
              :value="project.id"
              :disabled="busy"
            /><Folder :size="17" /><span>{{ project.name }}</span></label
          ><InfiniteLoad
            :page="workspace.state.projectsPage"
            label="移动目标项目滚动分页"
            :disabled="busy || !workspace.can('agent:project:page')"
            @load="workspace.loadProjects()"
          /></div
      ></template>
      <template v-else-if="action === 'archive'"
        ><p class="delete-explanation">
          正在将“{{ name }}”归档。归档后可从项目菜单的“已归档聊天”中恢复。
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
      <template v-else-if="action === 'archives'"
        ><div class="archived-list" data-scroll-root>
          <div
            v-for="chat in archivedChats"
            :key="chat.id"
            class="archived-row"
          >
            <span
              >{{ chat.title
              }}<small class="archived-project-name">{{
                chat.projectName || "无项目"
              }}</small></span
            ><button
              type="button"
              class="icon-button"
              :aria-label="`恢复聊天 ${chat.title}`"
              :disabled="busy || !workspace.can('agent:chat:restore')"
              @click="restore(chat.id)"
            >
              <ArchiveRestore :size="17" /></button
            ><button
              type="button"
              class="icon-button"
              :aria-label="`重命名归档聊天 ${chat.title}`"
              :disabled="busy || !workspace.can('agent:chat:rename')"
              @click="open('rename', chat.id)"
            >
              <Pencil :size="17" /></button
            ><button
              type="button"
              class="icon-button danger-action"
              :aria-label="`永久删除归档聊天 ${chat.title}`"
              :disabled="busy || !workspace.can('agent:chat:delete')"
              @click="open('delete', chat.id)"
            >
              <Trash2 :size="17" />
            </button>
          </div>
          <InfiniteLoad
            :page="workspace.state.archivedPage"
            label="归档聊天滚动分页"
            :disabled="busy || !workspace.can('agent:chat:archived')"
            @load="workspace.loadArchived()"
          />
          <p
            v-if="
              !archivedChats.length &&
              workspace.state.archivedPage.initialized &&
              !workspace.state.archivedPage.loading &&
              !workspace.state.archivedPage.error
            "
            class="muted share-placeholder"
          >
            暂无已归档聊天。
          </p>
        </div></template
      >
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <div class="project-dialog-actions">
        <button
          type="button"
          class="text-button"
          :disabled="busy"
          @click="close"
        >
          {{ action === "archives" ? "关闭" : "取消" }}</button
        ><button
          v-if="action !== 'archives'"
          class="primary"
          :class="{ 'danger-button': action === 'delete' }"
          :disabled="
            busy ||
            ((action === 'rename' || action === 'settings') && !name.trim())
          "
        >
          {{
            busy
              ? "正在提交…"
              : action === "delete"
                ? "永久删除"
                : action === "move"
                  ? "移动"
                  : action === "archive"
                    ? "重试归档"
                    : "保存"
          }}
        </button>
      </div>
    </form>
  </dialog>
</template>
