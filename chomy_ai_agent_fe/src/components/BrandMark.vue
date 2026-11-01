<script setup lang="ts">
// 品牌图形使用本地 SVG，不依赖第三方图片、远程字体或 ChatGPT 商标。
withDefaults(defineProps<{ size?: number }>(), { size: 32 });
</script>
<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 40 40"
    fill="none"
    aria-hidden="true"
  >
    <path
      d="M20 4 33.86 12v16L20 36 6.14 28V12L20 4Z"
      stroke="currentColor"
      stroke-width="2.4"
      stroke-linejoin="round"
    />
    <path
      d="m20 4 0 32M6.14 12 33.86 28M33.86 12 6.14 28"
      stroke="currentColor"
      stroke-width="2.4"
    />
    <path d="m13 8 14 24M27 8 13 32" stroke="currentColor" stroke-width="2.4" />
  </svg>
</template>
