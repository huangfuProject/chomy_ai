<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { workspace } from "../stores/workspace";
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

const route = useRoute(),
  router = useRouter();
// 路由切换时旧组件仍会执行卸载保存，捕获本窗口 ID，避免把旧草稿写入新窗口。
const chatId =
  typeof route.params.chatId === "string" ? route.params.chatId : undefined;
const projectId =
  typeof route.params.projectId === "string"
    ? route.params.projectId
    : undefined;
const currentChat = computed(() =>
  workspace.state.chats.find((chat) => chat.id === chatId),
);
const currentProject = computed(() =>
  workspace.state.projects.find(
    (project) => project.id === (currentChat.value?.projectId || projectId),
  ),
);
const draft = ref(currentChat.value?.draft || ""),
  notice = ref(""),
  sending = ref(false),
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
  if (sending.value) return;
  draft.value = prompt;
  composer.value?.focus();
}
/** 统一先以“新聊天”创建，首次提交调用后端生成名称并保存；消息发送接口仍待接入。 */
async function send() {
  if (!draft.value.trim() || sending.value) return;
  sending.value = true;
  const content = draft.value;
  try {
    let chat = currentChat.value;
    const created = !chat || chat.source === "draft";
    if (created)
      chat = await workspace.createChat(
        currentProject.value?.id ?? null,
        content,
        chat?.source === "draft" ? chat.id : undefined,
      );
    if (!chat) return;
    workspace.saveDraft(chat.id, content);
    // 命名失败不丢弃已经创建的聊天，也不把失败变成重复创建；错误由窗口内状态单独显示。
    try {
      await workspace.generateFirstChatName(chat.id, content);
    } catch {
      /* 保留默认名、完整草稿和首次命名文本，用户可显式重试。 */
    }
    if (created) {
      await router.push({
        path: `/chat/${chat.id}`,
        query: {
          pending: "1",
          ...(chat.projectId ? { projectId: chat.projectId } : {}),
        },
      });
    } else notice.value = "聊天服务暂未开放，你的草稿已保留在当前聊天窗口。";
  } catch (cause) {
    notice.value = cause instanceof Error ? cause.message : "暂时无法保存草稿";
  } finally {
    sending.value = false;
  }
}
const naming = computed(() =>
  currentChat.value ? workspace.state.naming[currentChat.value.id] : undefined,
);
/** 重试仍由状态层使用第一次提交的文本，避免把后续输入当成首次内容生成名称。 */
async function retryNaming() {
  if (!currentChat.value || sending.value) return;
  sending.value = true;
  try {
    await workspace.generateFirstChatName(currentChat.value.id, draft.value);
  } catch {
    /* 错误与默认名称保留在该聊天状态中，界面提供再次重试入口。 */
  } finally {
    sending.value = false;
  }
}
/** 输入法组合期间不处理 Enter，Shift+Enter 保留多行输入。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    send();
  }
}
// 每个窗口使用独立草稿；防抖减少本地存储写入，离开窗口时立即保存最后一次输入。
let saveTimer: ReturnType<typeof setTimeout> | undefined;
function saveCurrentDraft() {
  if (!currentChat.value) return;
  try {
    workspace.saveDraft(currentChat.value.id, draft.value);
  } catch (cause) {
    notice.value = cause instanceof Error ? cause.message : "草稿保存失败";
  }
}
watch(draft, () => {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveCurrentDraft, 250);
});
if (route.query.pending === "1") {
  notice.value = "聊天服务暂未开放，你的草稿已保留在当前聊天窗口。";
  // 提示只在本次创建窗口后显示，移除一次性标记避免刷新时反复触发。
  void router.replace({
    path: route.path,
    query: currentChat.value?.projectId
      ? { projectId: currentChat.value.projectId }
      : {},
  });
}
onBeforeUnmount(() => {
  clearTimeout(saveTimer);
  saveCurrentDraft();
});
</script>

<template>
  <main class="chat-page">
    <section class="chat-welcome">
      <div class="welcome-symbol"><BrandMark :size="43" /></div>
      <div class="eyebrow">
        {{ currentProject ? "项目工作空间" : "一点好奇，无限可能" }}
      </div>
      <h1>
        {{
          currentChat && currentChat.title !== "新聊天"
            ? currentChat.title
            : currentProject?.name || "今天，有什么新想法？"
        }}
      </h1>
      <p>
        {{
          currentProject?.description ||
          (currentProject
            ? "从一个新对话开始，探索这个项目的更多可能。"
            : "写作、思考、探索。让 Chomy AI 陪你迈出第一步。")
        }}
      </p>
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
          :disabled="sending"
          @keydown="onKeydown"
        />
        <div class="composer-toolbar">
          <span><Sparkles :size="15" />让想法生长</span
          ><button
            class="send-button"
            aria-label="发送消息"
            :disabled="
              !draft.trim() ||
              sending ||
              ((!currentChat || currentChat.source === 'draft') &&
                !workspace.can('agent:chat:create'))
            "
          >
            <ArrowUp :size="21" />
          </button>
        </div>
      </form>
      <p v-if="naming?.loading" class="chat-hint" role="status">
        正在生成聊天名称…
      </p>
      <div v-if="naming?.error" class="chat-notice" role="alert">
        <MessageCircle :size="18" /><span
          >名称生成失败：{{ naming.error }}。草稿已保留。</span
        ><button
          type="button"
          class="text-button"
          :disabled="sending"
          @click="retryNaming"
        >
          重试命名
        </button>
      </div>
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
          :disabled="sending"
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
