<script setup lang="ts">
import { computed } from "vue";
import { workspace } from "../stores/workspace";
import { RouterLink } from "vue-router";
import { Pencil, Share, Archive, FolderInput, Trash2 } from "lucide-vue-next";
import SidebarMenu from "./SidebarMenu.vue";
import type { WorkspaceChat } from "../types/workspace";

// 行内链接与操作按钮平级，点击省略号不会误触聊天导航。
const props = defineProps<{ chat: WorkspaceChat }>();
const emit = defineEmits<{ action: [action: string, id: string] }>();
const items = computed(() => [
  {
    id: "rename",
    label: "重命名",
    icon: Pencil,
    disabled:
      props.chat.source === "remote" && !workspace.can("agent:chat:rename"),
  },
  {
    id: "share",
    label: "分享",
    icon: Share,
    disabled: true,
    hint: "分享功能尚未开放",
  },
  {
    id: "archive",
    label: "归档",
    icon: Archive,
    disabled:
      props.chat.source !== "remote" || !workspace.can("agent:chat:archive"),
  },
  {
    id: "move",
    label: "移至项目",
    icon: FolderInput,
    disabled:
      props.chat.source !== "remote" || !workspace.can("agent:chat:move"),
  },
  {
    id: "delete",
    label: "永久删除",
    icon: Trash2,
    danger: true,
    disabled:
      props.chat.source === "remote" && !workspace.can("agent:chat:delete"),
    separator: true,
  },
]);
</script>
<template>
  <div class="conversation-row">
    <RouterLink
      :to="{
        path: `/chat/${chat.id}`,
        query: chat.projectId ? { projectId: chat.projectId } : {},
      }"
      :title="chat.title"
      ><span>{{ chat.title }}</span
      ><small v-if="chat.source === 'draft'" class="draft-badge"
        >未发送</small
      ></RouterLink
    ><SidebarMenu
      :label="`聊天 ${chat.title} 的更多操作`"
      :items="items"
      @select="emit('action', $event, chat.id)"
    />
  </div>
</template>
