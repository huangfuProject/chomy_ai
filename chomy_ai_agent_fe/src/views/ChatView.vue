<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import {
  ArrowUp,
  Sparkles,
  Code2,
  PenLine,
  Lightbulb,
  X,
  MessageCircle,
} from "lucide-vue-next";
import BrandMark from "../components/BrandMark.vue";

const draft = ref(""),
  notice = ref(""),
  composer = ref<HTMLTextAreaElement | null>(null);
const suggestions = [
  {
    icon: PenLine,
    title: "写点什么",
    detail: "把零散灵感，变成好表达",
    prompt: "帮我整理一个写作思路",
  },
  {
    icon: Lightbulb,
    title: "打开思路",
    detail: "换个角度，发现新的可能",
    prompt: "和我一起头脑风暴一个新想法",
  },
  {
    icon: Code2,
    title: "一起写代码",
    detail: "从一个问题，到一个解法",
    prompt: "帮我分析一个代码问题",
  },
];
/** 仅填入草稿，不伪造 AI 回复；聊天服务接入后可在此替换发送适配层。 */
function choosePrompt(prompt: string) {
  draft.value = prompt;
  composer.value?.focus();
}
function send() {
  if (!draft.value.trim()) return;
  notice.value = "聊天服务暂未开放，你可以先保留这份灵感，稍后再来。";
}
/** 输入法组合期间不处理 Enter，Shift+Enter 保留多行输入。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    send();
  }
}
function reset() {
  draft.value = "";
  notice.value = "";
  composer.value?.focus();
}
window.addEventListener("new-chat", reset);
onBeforeUnmount(() => window.removeEventListener("new-chat", reset));
</script>

<template>
  <main class="chat-page">
    <section class="chat-welcome">
      <div class="welcome-symbol"><BrandMark :size="43" /></div>
      <div class="eyebrow">一点好奇，无限可能</div>
      <h1>今天，有什么新想法？</h1>
      <p>写作、思考、探索。让 Chomy AI 陪你迈出第一步。</p>
    </section>
    <div class="chat-content">
      <form class="composer" @submit.prevent="send">
        <label class="sr-only" for="message">向 Chomy AI 发送消息</label
        ><textarea
          id="message"
          ref="composer"
          v-model="draft"
          placeholder="有什么想聊的，尽管告诉我…"
          rows="2"
          maxlength="20000"
          @keydown="onKeydown"
        />
        <div class="composer-toolbar">
          <span><Sparkles :size="15" />让想法生长</span
          ><button
            class="send-button"
            aria-label="发送消息"
            :disabled="!draft.trim()"
          >
            <ArrowUp :size="21" />
          </button>
        </div>
      </form>
      <div v-if="notice" class="chat-notice" role="status">
        <MessageCircle :size="18" /><span>{{ notice }}</span
        ><button class="icon-button" aria-label="关闭提示" @click="notice = ''">
          <X :size="16" />
        </button>
      </div>
      <div class="suggestions">
        <button
          v-for="suggestion in suggestions"
          :key="suggestion.title"
          @click="choosePrompt(suggestion.prompt)"
        >
          <component :is="suggestion.icon" :size="19" /><strong>{{
            suggestion.title
          }}</strong
          ><span>{{ suggestion.detail }}</span>
        </button>
      </div>
      <p class="chat-hint">不必有完整的答案，从一个问题开始就好。</p>
    </div>
    <footer class="chat-footer">
      Chomy AI 也可能会犯错，请核实重要信息。<span>聊天服务即将开放</span>
    </footer>
  </main>
</template>
