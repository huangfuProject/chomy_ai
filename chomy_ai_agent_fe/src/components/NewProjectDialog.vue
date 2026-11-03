<script setup lang="ts">
import { nextTick, ref } from "vue";
import { FolderPlus, X } from "lucide-vue-next";
import { workspace } from "../stores/workspace";

const emit = defineEmits<{ created: [id: string] }>();
const dialog = ref<HTMLDialogElement | null>(null),
  nameInput = ref<HTMLInputElement | null>(null);
const name = ref(""),
  description = ref(""),
  error = ref(""),
  busy = ref(false);
/** 原生模态框提供焦点约束和 Escape 关闭，打开时清理上次未提交的表单。 */
async function open() {
  name.value = "";
  description.value = "";
  error.value = "";
  dialog.value?.showModal();
  await nextTick();
  nameInput.value?.focus();
}
async function submit() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const project = await workspace.createProject(
      name.value,
      description.value,
    );
    dialog.value?.close();
    emit("created", project.id);
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "项目创建失败，请重试";
  } finally {
    busy.value = false;
  }
}
defineExpose({ open });
</script>

<template>
  <dialog
    ref="dialog"
    class="project-dialog"
    aria-labelledby="new-project-title"
    @cancel="busy && $event.preventDefault()"
  >
    <form @submit.prevent="submit">
      <div class="dialog-heading">
        <span class="project-dialog-icon"><FolderPlus :size="23" /></span
        ><button
          type="button"
          class="icon-button"
          aria-label="关闭新建项目"
          @click="!busy && dialog?.close()"
          :disabled="busy"
        >
          <X :size="20" />
        </button>
      </div>
      <h2 id="new-project-title">创建新项目</h2>
      <p class="muted">把同一个目标下的想法和对话，放在一起。</p>
      <label for="project-name">项目名 <span>必填</span></label
      ><input
        id="project-name"
        ref="nameInput"
        v-model="name"
        placeholder="例如：我的第一个项目"
        maxlength="128"
        :disabled="busy"
        required
      />
      <label for="project-description">项目介绍 <span>选填</span></label
      ><textarea
        id="project-description"
        v-model="description"
        placeholder="介绍一下这个项目，或你想达成的目标…"
        rows="4"
        maxlength="2000"
        :disabled="busy"
      /><small class="character-count">{{ description.length }} / 2000</small>
      <p v-if="error" class="error-message" role="alert">{{ error }}</p>
      <div class="project-dialog-actions">
        <button
          type="button"
          class="text-button"
          @click="!busy && dialog?.close()"
          :disabled="busy"
        >
          取消</button
        ><button class="primary" :disabled="busy || !name.trim()">
          {{ busy ? "正在创建…" : "创建项目" }}
        </button>
      </div>
    </form>
  </dialog>
</template>
