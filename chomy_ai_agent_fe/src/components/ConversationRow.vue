<script setup lang="ts">
import { RouterLink } from "vue-router";
import { Pencil, Share, Archive, FolderInput, Trash2 } from "lucide-vue-next";
import SidebarMenu from "./SidebarMenu.vue";
import type { WorkspaceChat } from "../types/workspace";

// 行内链接与操作按钮平级，点击省略号不会误触聊天导航。
defineProps<{ chat: WorkspaceChat }>();
const emit = defineEmits<{ action: [action: string, id: string] }>();
const items = [
  { id: "rename", label: "重命名", icon: Pencil },
  { id: "share", label: "分享", icon: Share },
  { id: "archive", label: "归档", icon: Archive },
  { id: "move", label: "移至项目", icon: FolderInput },
  {
    id: "delete",
    label: "永久删除",
    icon: Trash2,
    danger: true,
    separator: true,
  },
];
</script>
<template>
  <div class="conversation-row">
    <RouterLink :to="`/chat/${chat.id}`" :title="chat.title"
      ><span>{{ chat.title }}</span></RouterLink
    ><SidebarMenu
      :label="`聊天 ${chat.title} 的更多操作`"
      :items="items"
      @select="emit('action', $event, chat.id)"
    />
  </div>
</template>
