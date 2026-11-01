<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import {
  PanelLeft,
  SquarePen,
  ChevronUp,
  ChevronRight,
  LogOut,
  ArrowLeftRight,
  X,
  LayoutGrid,
  Folder,
  FolderOpen,
  FolderPlus,
  Plus,
  MessageSquare,
  ChevronDown,
  Share,
  Pencil,
  Settings,
  Pin,
  Trash2,
  Archive,
} from "lucide-vue-next";
import SidebarMenu from "../components/SidebarMenu.vue";
import ConversationRow from "../components/ConversationRow.vue";
import WorkspaceActionDialog from "../components/WorkspaceActionDialog.vue";
import type { WorkspaceProject } from "../types/workspace";
import BrandMark from "../components/BrandMark.vue";
import NewProjectDialog from "../components/NewProjectDialog.vue";
import { clearSession, session } from "../stores/session";
import { workspace } from "../stores/workspace";
import { logout } from "../api/auth";
import { areaMenus, hasArea, menuTarget } from "../utils/permissions";
import type { Area } from "../types/api";

const route = useRoute(),
  router = useRouter();
const mobileOpen = ref(false),
  accountOpen = ref(false),
  signingOut = ref(false),
  error = ref("");
const actionDialog = ref<InstanceType<typeof WorkspaceActionDialog> | null>(
  null,
);
const projectHeadingItems = [
  { id: "create", label: "创建项目", icon: FolderPlus },
  { id: "archives", label: "已归档聊天", icon: Archive },
];
/** 项目操作顺序遵循参考界面，删除与常规动作分组呈现。 */
function projectItems(project: WorkspaceProject) {
  return [
    { id: "share", label: "分享项目", icon: Share },
    { id: "rename", label: "重命名项目", icon: Pencil },
    { id: "settings", label: "项目设置", icon: Settings },
    { id: "home", label: "项目主页", icon: Folder },
    {
      id: "pin",
      label: project.pinned ? "取消置顶" : "置顶项目",
      icon: Pin,
      separator: true,
    },
    { id: "delete", label: "删除项目", icon: Trash2, danger: true },
  ];
}
function projectAction(action: string, id: string) {
  if (action === "home") {
    void router.push(`/projects/${id}`);
    return;
  }
  if (action === "pin") {
    workspace.toggleProjectPin(id);
    return;
  }
  void actionDialog.value?.open(action, id, "project");
}
function chatAction(action: string, id: string) {
  void actionDialog.value?.open(action, id, "chat");
}
function headingAction(action: string) {
  if (action === "create") void projectDialog.value?.open();
  else void actionDialog.value?.open("archives");
}
// 聊天移至其他项目时地址不变，也要展开目标目录，避免当前聊天从导航中消失。
watch(
  () =>
    workspace.state.chats.find((c) => c.id === route.params.chatId)?.projectId,
  (id) => {
    if (id && !expandedProjects.value.includes(id))
      expandedProjects.value.push(id);
  },
);
const projectDialog = ref<InstanceType<typeof NewProjectDialog> | null>(null);
const accountSection = ref<HTMLElement | null>(null),
  expandedProjects = ref<string[]>([]);
const media = window.matchMedia("(max-width: 760px)"),
  isMobile = ref(media.matches);
const area = computed<Area>(() =>
  route.meta.area === "backend" ? "backend" : "frontend",
);
const permissions = computed(() => session.data?.permissions ?? []);
const backendMenus = computed(() => areaMenus(permissions.value, "backend"));
const frontendMenus = computed(() => areaMenus(permissions.value, "frontend"));
const canFrontend = computed(() => hasArea(permissions.value, "frontend"));
const canBackend = computed(() => hasArea(permissions.value, "backend"));
const displayName = computed(
  () => session.data?.user.nickname || session.data?.user.username || "用户",
);
const standaloneChats = computed(() => workspace.listStandaloneChats());
const projects = computed(() => workspace.listProjects());
const sidebarHidden = computed(() =>
  isMobile.value ? !mobileOpen.value : workspace.state.sidebarCollapsed,
);

// 用户变更时清空上个用户的展开状态；仓储加载按账号隔离，不读取另一个用户的草稿。
watch(
  () => session.data?.user.id,
  (id) => {
    workspace.load(id ?? "");
    expandedProjects.value = [];
  },
  { immediate: true },
);
/** 导航完成后关闭浮层，并让当前项目的聊天在目录树中可见。 */
watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false;
    accountOpen.value = false;
    error.value = "";
    const chat = workspace.state.chats.find(
      (c) => c.id === route.params.chatId,
    );
    const projectId = chat?.projectId || String(route.params.projectId || "");
    if (projectId && !expandedProjects.value.includes(projectId))
      expandedProjects.value.push(projectId);
  },
  { immediate: true },
);
function onViewportChange(event: MediaQueryListEvent) {
  isMobile.value = event.matches;
  mobileOpen.value = false;
  accountOpen.value = false;
}
media.addEventListener("change", onViewportChange);
/** 桌面折叠保存为用户偏好；手机只控制临时抽屉，不改动桌面设置。 */
function toggleSidebar() {
  accountOpen.value = false;
  if (isMobile.value) mobileOpen.value = !mobileOpen.value;
  else workspace.toggleSidebar();
}
function closeSidebar() {
  accountOpen.value = false;
  if (isMobile.value) mobileOpen.value = false;
  else if (!workspace.state.sidebarCollapsed) workspace.toggleSidebar();
}
function toggleProject(id: string) {
  expandedProjects.value = expandedProjects.value.includes(id)
    ? expandedProjects.value.filter((value) => value !== id)
    : [...expandedProjects.value, id];
}
/** 每次点击都创建独立窗口，项目聊天只出现在所属项目下，避免混入普通聊天列表。 */
async function startChat(projectId: string | null = null) {
  error.value = "";
  try {
    const chat = workspace.createChat(projectId);
    if (projectId && !expandedProjects.value.includes(projectId))
      expandedProjects.value.push(projectId);
    await router.push(`/chat/${chat.id}`);
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "聊天创建失败，请重试";
  }
}
async function projectCreated(id: string) {
  expandedProjects.value.push(id);
  await router.push(`/projects/${id}`);
}
/** 点击账号区外部时收起浮层，保留弹窗内部链接和操作的原生键盘行为。 */
function closeAccountOutside(event: PointerEvent) {
  if (
    event.target instanceof Node &&
    !accountSection.value?.contains(event.target)
  )
    accountOpen.value = false;
}
document.addEventListener("pointerdown", closeAccountOutside);
const expiryTimer = window.setTimeout(
  () => {
    clearSession();
    void router.replace("/login?reason=expired");
  },
  Math.min(Math.max(0, session.expiresAt - Date.now()), 2_147_483_647),
);
onBeforeUnmount(() => {
  window.clearTimeout(expiryTimer);
  media.removeEventListener("change", onViewportChange);
  document.removeEventListener("pointerdown", closeAccountOutside);
});
/** 撤销成功才退出；失败时仍明确反馈，不声称服务端会话已清理。 */
async function signOut() {
  if (signingOut.value) return;
  signingOut.value = true;
  error.value = "";
  try {
    await logout();
    clearSession();
    workspace.load("");
    await router.replace("/login");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "退出失败，请重试";
  } finally {
    signingOut.value = false;
  }
}
</script>

<template>
  <div
    class="workspace"
    :class="{ 'sidebar-collapsed': workspace.state.sidebarCollapsed }"
    @keydown.esc="
      accountOpen = false;
      mobileOpen = false;
    "
  >
    <button
      v-if="mobileOpen"
      class="sidebar-overlay"
      aria-label="关闭侧栏遮罩"
      @click="closeSidebar"
    />
    <aside
      id="workspace-sidebar"
      class="sidebar"
      :class="{ 'is-open': mobileOpen }"
      :inert="sidebarHidden"
      aria-label="工作空间侧栏"
    >
      <div class="sidebar-top">
        <RouterLink :to="area === 'frontend' ? '/chat' : '/admin'" class="brand"
          ><BrandMark :size="27" /> Chomy <span>AI</span></RouterLink
        ><button
          class="icon-button sidebar-close"
          aria-label="隐藏侧栏"
          aria-controls="workspace-sidebar"
          :aria-expanded="!sidebarHidden"
          @click="closeSidebar"
        >
          <PanelLeft :size="19" />
        </button>
      </div>
      <div class="sidebar-content">
        <template v-if="area === 'frontend'">
          <!-- 第一部分：独立聊天。创建动作先生成 ID，再导航到对应窗口。 -->
          <section class="sidebar-section chat-section" aria-label="聊天栏目">
            <button class="new-chat" @click="startChat()">
              <SquarePen :size="18" />新聊天<Plus
                :size="17"
                class="button-trailing"
              />
            </button>
            <nav class="conversation-list" aria-label="无项目聊天列表">
              <ConversationRow
                v-for="chat in standaloneChats"
                :key="chat.id"
                :chat="chat"
                @action="chatAction"
              />
            </nav>
            <p v-if="!standaloneChats.length" class="sidebar-list-empty">
              你的新对话会出现在这里
            </p>
          </section>
          <!-- 第二部分：项目文件夹与各自的聊天，目录按钮和加号分别操作，避免嵌套交互元素。 -->
          <section
            class="sidebar-section projects-section"
            aria-labelledby="projects-heading"
          >
            <div class="section-heading project-section-heading">
              <h2 id="projects-heading">
                <SidebarMenu
                  label="项目选项"
                  :items="projectHeadingItems"
                  @select="headingAction"
                  ><span>项目</span><ChevronDown :size="14"
                /></SidebarMenu>
              </h2>
            </div>
            <nav class="project-list" aria-label="项目列表">
              <div
                v-for="project in projects"
                :key="project.id"
                class="project-folder"
              >
                <div
                  class="project-folder-row"
                  :class="{
                    'active-project':
                      route.params.projectId === project.id ||
                      workspace.state.chats.some(
                        (c) =>
                          c.id === route.params.chatId &&
                          c.projectId === project.id,
                      ),
                  }"
                >
                  <button
                    class="folder-chevron"
                    :aria-label="`${expandedProjects.includes(project.id) ? '收起' : '展开'}项目 ${project.name}`"
                    :aria-expanded="expandedProjects.includes(project.id)"
                    @click="toggleProject(project.id)"
                  >
                    <ChevronRight
                      :size="13"
                      :class="{
                        'folder-expanded': expandedProjects.includes(
                          project.id,
                        ),
                      }"
                    /></button
                  ><RouterLink
                    :to="`/projects/${project.id}`"
                    :title="project.name"
                    ><FolderOpen
                      v-if="expandedProjects.includes(project.id)"
                      :size="17"
                    /><Folder v-else :size="17" /><span>{{
                      project.name
                    }}</span></RouterLink
                  ><SidebarMenu
                    :label="`项目 ${project.name} 的更多操作`"
                    :items="projectItems(project)"
                    @select="projectAction($event, project.id)"
                  /><button
                    class="project-add-chat"
                    :aria-label="`在项目 ${project.name} 下新建聊天`"
                    @click="startChat(project.id)"
                  >
                    <SquarePen :size="16" />
                  </button>
                </div>
                <div
                  v-if="expandedProjects.includes(project.id)"
                  class="project-conversations"
                >
                  <ConversationRow
                    v-for="chat in workspace.listProjectChats(project.id)"
                    :key="chat.id"
                    :chat="chat"
                    @action="chatAction"
                  />
                  <p
                    v-if="!workspace.listProjectChats(project.id).length"
                    class="sidebar-list-empty"
                  >
                    点击右侧图标开始项目对话
                  </p>
                </div>
              </div>
            </nav>
            <p v-if="!projects.length" class="sidebar-list-empty">
              点击“项目”旁的箭头创建项目
            </p>
          </section>
        </template>
        <!-- 后台保留自己的资源导航，前台的聊天与项目不混入管理菜单。 -->
        <template v-else
          ><div class="nav-caption">管理工作空间</div>
          <nav class="workspace-nav" aria-label="用户后台菜单">
            <RouterLink
              v-for="menu in backendMenus"
              :key="menu.id"
              :to="menuTarget(menu, 'backend')"
              ><LayoutGrid :size="18" />{{ menu.permissionName }}</RouterLink
            >
          </nav></template
        >
      </div>
      <p
        v-if="workspace.state.storageWarning && area === 'frontend'"
        class="storage-warning"
        role="status"
      >
        {{ workspace.state.storageWarning }}
      </p>
      <!-- 第三部分：所有前台授权菜单集中在头像浮层；后台入口固定为退出上方的最后一个导航操作。 -->
      <div ref="accountSection" class="account-section">
        <p v-if="error" role="alert" class="error-message">{{ error }}</p>
        <div v-if="accountOpen" id="account-popover" class="account-popover">
          <div class="account-caption">{{ session.data?.user.username }}</div>
          <nav
            v-if="frontendMenus.length"
            class="account-menu-list"
            aria-label="用户前台菜单"
          >
            <RouterLink
              v-for="menu in frontendMenus"
              :key="menu.id"
              :to="menuTarget(menu, 'frontend')"
              @click="accountOpen = false"
              ><LayoutGrid :size="17" /><span>{{
                menu.permissionName
              }}</span></RouterLink
            >
          </nav>
          <div class="account-actions">
            <button
              v-if="area === 'backend' && canFrontend"
              @click="router.push('/chat')"
            >
              <ArrowLeftRight :size="17" />跳转到前台</button
            ><button
              v-if="area === 'frontend' && canBackend"
              @click="router.push('/admin')"
            >
              <ArrowLeftRight :size="17" />管理后端</button
            ><button :disabled="signingOut" @click="signOut">
              <LogOut :size="17" />{{ signingOut ? "正在退出…" : "退出登录" }}
            </button>
          </div>
        </div>
        <button
          class="account-trigger"
          aria-controls="account-popover"
          :aria-expanded="accountOpen"
          @click="accountOpen = !accountOpen"
        >
          <span class="avatar">{{ displayName.slice(0, 1).toUpperCase() }}</span
          ><span class="account-info"
            ><strong>{{ displayName }}</strong
            ><small>{{
              area === "frontend" ? "个人工作空间" : "后台管理"
            }}</small></span
          ><ChevronUp :size="16" :class="{ rotated: accountOpen }" />
        </button>
      </div>
    </aside>
    <div class="workspace-body">
      <header class="workspace-header">
        <div class="workspace-title">
          <button
            v-if="sidebarHidden"
            class="icon-button sidebar-open"
            aria-label="展开侧栏"
            aria-controls="workspace-sidebar"
            :aria-expanded="!sidebarHidden"
            @click="toggleSidebar"
          >
            <PanelLeft :size="21" /></button
          ><span>{{ area === "frontend" ? "Chomy AI" : "管理后台" }}</span
          ><small>{{
            area === "frontend" ? "你的智能伙伴" : "工作空间"
          }}</small>
        </div>
        <span class="workspace-status"
          ><span class="tiny-dot" />{{
            area === "frontend" ? "灵感，从这里开始" : "用户后台"
          }}</span
        >
      </header>
      <RouterView :key="route.path" />
    </div>
    <WorkspaceActionDialog v-if="area === 'frontend'" ref="actionDialog" />
    <NewProjectDialog
      v-if="area === 'frontend'"
      ref="projectDialog"
      @created="projectCreated"
    />
  </div>
</template>
