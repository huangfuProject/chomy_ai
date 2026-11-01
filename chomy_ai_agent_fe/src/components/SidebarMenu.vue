<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, type Component } from "vue";
import { Ellipsis } from "lucide-vue-next";

/** 通用侧栏浮层：传送到 body 避免被滚动列表截断，所有操作使用原生按钮。 */
const props = defineProps<{
  label: string;
  items: {
    id: string;
    label: string;
    icon: Component;
    danger?: boolean;
    separator?: boolean;
  }[];
}>();
const emit = defineEmits<{ select: [action: string] }>();
const open = ref(false),
  trigger = ref<HTMLButtonElement | null>(null),
  menu = ref<HTMLElement | null>(null);
const position = ref({ left: "0px", top: "0px" });
function close(restoreFocus = false) {
  open.value = false;
  if (restoreFocus) trigger.value?.focus();
}
async function toggle() {
  if (open.value) {
    close();
    return;
  }
  // 开新菜单前通知其他菜单关闭，保证同一时刻只有一个浮层。
  window.dispatchEvent(new Event("sidebar-menu-open"));
  const box = trigger.value!.getBoundingClientRect();
  const width = Math.min(234, window.innerWidth - 24),
    height = props.items.length * 41 + 28;
  position.value = {
    left: `${Math.max(12, Math.min(box.right + 6, window.innerWidth - width - 12))}px`,
    top: `${Math.max(12, Math.min(box.top, window.innerHeight - height - 12))}px`,
  };
  open.value = true;
  await nextTick();
  menu.value?.querySelector<HTMLButtonElement>("button")?.focus();
}
function outside(event: PointerEvent) {
  if (
    event.target instanceof Node &&
    !menu.value?.contains(event.target) &&
    !trigger.value?.contains(event.target)
  )
    close();
}
/** 菜单支持上下方向键循环及首尾定位，Escape 归还触发按钮焦点。 */
function keyboard(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    event.stopPropagation();
    close(true);
    return;
  }
  if (event.key === "Tab") {
    close();
    return;
  }
  const buttons = [
    ...(menu.value?.querySelectorAll<HTMLButtonElement>("button") ?? []),
  ];
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
  const next =
    event.key === "ArrowDown"
      ? (index + 1) % buttons.length
      : event.key === "ArrowUp"
        ? (index + buttons.length - 1) % buttons.length
        : event.key === "Home"
          ? 0
          : event.key === "End"
            ? buttons.length - 1
            : -1;
  if (next >= 0) {
    event.preventDefault();
    buttons[next]?.focus();
  }
}
function select(action: string) {
  close(true);
  emit("select", action);
}
const dismiss = () => close();
document.addEventListener("pointerdown", outside);
window.addEventListener("sidebar-menu-open", dismiss);
window.addEventListener("resize", dismiss);
// 用户滚动列表时关闭浮层，防止它继续停留在已经移走的聊天旁边。
window.addEventListener("scroll", dismiss, true);
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", outside);
  window.removeEventListener("sidebar-menu-open", dismiss);
  window.removeEventListener("resize", dismiss);
  window.removeEventListener("scroll", dismiss, true);
});
</script>
<template>
  <button
    ref="trigger"
    type="button"
    class="item-menu-trigger"
    :class="{ 'menu-is-open': open }"
    :aria-label="label"
    aria-haspopup="menu"
    :aria-expanded="open"
    @click.stop="toggle"
  >
    <slot><Ellipsis :size="18" /></slot>
  </button>
  <Teleport to="body"
    ><div
      v-if="open"
      ref="menu"
      class="sidebar-action-menu"
      :style="position"
      role="menu"
      :aria-label="label"
      @keydown="keyboard"
    >
      <template v-for="item in items" :key="item.id"
        ><div
          v-if="item.separator"
          class="action-menu-separator"
          role="separator"
        />
        <button
          role="menuitem"
          :class="{ 'danger-action': item.danger }"
          @click="select(item.id)"
        >
          <component :is="item.icon" :size="18" /><span>{{ item.label }}</span>
        </button></template
      >
    </div></Teleport
  >
</template>
