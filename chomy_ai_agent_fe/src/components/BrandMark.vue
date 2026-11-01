<script setup lang="ts">
// 统一使用用户提供的透明 PNG，保留原始色彩和透明背景，不裁切或拉伸品牌图案。
// size 表示显示区域的边长（像素）；各页面继续按原有布局传入大小。
withDefaults(defineProps<{ size?: number }>(), { size: 32 });
// 使用 Vite 的基础路径，部署到子目录时也能正确定位公共品牌资源。
const logoUrl = `${import.meta.env.BASE_URL}brand-logo.png`;
</script>

<template>
  <!-- 图案旁已有品牌名称或页面标题，使用空替代文本，避免读屏重复朗读。 -->
  <img
    :src="logoUrl"
    :width="size"
    :height="size"
    alt=""
    aria-hidden="true"
    class="brand-mark"
  />
</template>

<style scoped>
/* 保持正方形占位和原图比例；禁止侧栏等 flex 布局压缩图案。 */
.brand-mark {
  display: inline-block;
  object-fit: contain;
  flex-shrink: 0;
  vertical-align: middle;
}
</style>
