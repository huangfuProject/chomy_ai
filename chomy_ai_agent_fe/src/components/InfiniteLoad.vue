<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { hasMore } from "../stores/workspace";
import type { WorkspacePage } from "../types/workspace";

/** 通用滚动分页尾部；每个列表传入自己的页状态，错误时等待用户重试，避免请求风暴。 */
const props = defineProps<{
  page: WorkspacePage;
  label: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{ load: [] }>();
const sentinel = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | undefined,
  resize: ResizeObserver | undefined;
let root: HTMLElement | null = null;
let scrollFrame = 0;
// DOM 追加和用户滚动可能发生在同一帧，观察器未必产生新的交叉事件；同时监听真实滚动兜底。
function onScroll() {
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    void check();
  });
}
async function check() {
  await nextTick();
  if (
    props.disabled ||
    props.page.loading ||
    props.page.error ||
    !hasMore(props.page) ||
    !sentinel.value
  )
    return;
  const box = sentinel.value.getBoundingClientRect(),
    bounds = root?.getBoundingClientRect();
  // 第一页全是归档记录时尾部可能一直可见，完成后重新检查直到填满容器或到达末页。
  if (
    box.height > 0 &&
    box.top <= (bounds?.bottom ?? innerHeight) + 120 &&
    box.bottom >= (bounds?.top ?? 0)
  )
    emit("load");
}
onMounted(() => {
  root = sentinel.value?.closest<HTMLElement>("[data-scroll-root]") ?? null;
  (root ?? window).addEventListener("scroll", onScroll, { passive: true });
  if (typeof IntersectionObserver !== "undefined") {
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) void check();
      },
      { root, rootMargin: "0px 0px 120px 0px" },
    );
    if (sentinel.value) observer.observe(sentinel.value);
  }
  if (root && typeof ResizeObserver !== "undefined") {
    resize = new ResizeObserver(() => {
      void check();
    });
    resize.observe(root);
  }
  void check();
});
watch(
  () => [
    props.page.loading,
    props.page.current,
    props.page.version,
    props.disabled,
  ],
  () => {
    void check();
  },
);
onBeforeUnmount(() => {
  (root ?? window).removeEventListener("scroll", onScroll);
  cancelAnimationFrame(scrollFrame);
  observer?.disconnect();
  resize?.disconnect();
});
</script>
<template>
  <div ref="sentinel" class="infinite-load" :aria-label="label">
    <template v-if="!disabled"
      ><span v-if="page.loading" role="status">正在加载…</span>
      <div v-else-if="page.error" class="list-load-error" role="alert">
        <span>{{ page.error }}</span
        ><button type="button" class="text-button" @click="emit('load')">
          重试
        </button>
      </div>
      <button
        v-else-if="hasMore(page)"
        type="button"
        class="text-button"
        @click="emit('load')"
      >
        加载更多</button
      ><span v-else-if="page.total > 0" class="list-end"
        >已加载全部</span
      ></template
    >
  </div>
</template>
