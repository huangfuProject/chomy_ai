import { createRouter, createWebHistory } from "vue-router";
import { clearSession, session } from "../stores/session";
import { getProfile } from "../api/auth";
import {
  areaMenus,
  hasArea,
  landingPath,
  menuTarget,
} from "../utils/permissions";
import type { Area } from "../types/api";
import { workspace } from "../stores/workspace";
import LoginView from "../views/LoginView.vue";
import WorkspaceLayout from "../layouts/WorkspaceLayout.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", component: LoginView },
    {
      path: "/no-access",
      component: () => import("../views/NoAccessView.vue"),
    },
    {
      path: "/",
      component: WorkspaceLayout,
      children: [
        {
          path: "chat",
          component: () => import("../views/ChatView.vue"),
          meta: { area: "frontend" },
        },
        // 本地窗口和项目均归属前台，后续列表接口只需替换仓储适配实现。
        {
          path: "chat/:chatId",
          component: () => import("../views/ChatView.vue"),
          meta: { area: "frontend" },
        },
        {
          path: "projects/:projectId",
          component: () => import("../views/ChatView.vue"),
          meta: { area: "frontend" },
        },
        {
          path: "agents",
          component: () => import("../views/ChatView.vue"),
          meta: { area: "frontend", menu: true },
        },
        {
          path: "admin",
          component: () => import("../views/AdminHomeView.vue"),
          meta: { area: "backend" },
        },
        {
          path: "admin/user-management",
          component: () => import("../views/UsersView.vue"),
          meta: { area: "backend", menu: true },
        },
        {
          path: "front/resource/:id",
          component: () => import("../views/ResourceView.vue"),
          meta: { area: "frontend", menu: true },
        },
        {
          path: "admin/resource/:id",
          component: () => import("../views/ResourceView.vue"),
          meta: { area: "backend", menu: true },
        },
      ],
    },
    {
      path: "/:pathMatch(.*)*",
      component: () => import("../views/NotFoundView.vue"),
    },
  ],
});

/** 未登录、工作区、菜单依次校验，手动输入 URL 和浏览器后退同样受保护。 */
router.beforeEach(async (to) => {
  // 每次导航都复核过期时间，包括后台首页跳转和浏览器历史导航。
  if (session.data && session.expiresAt <= Date.now()) clearSession();
  if (!session.data) return to.path === "/login" ? true : "/login";
  // 恢复时不能仅凭客户端保存的 JWT 存在就放行，必须请求服务端验证。
  if (!session.verified) {
    try {
      const profile = await getProfile();
      if (!session.data) return "/login";
      session.data.user = profile;
      session.verified = true;
    } catch {
      // 网络/503 也不放行；登录页保留错误提示和重试机会。
      return to.path === "/login"
        ? true
        : { path: "/login", query: { reason: "verify" } };
    }
  }
  const permissions = session.data?.permissions ?? [];
  // 本地记录只在服务器认证通过后按当前账号加载，禁止通过另一个账号的 URL 打开窗口。
  workspace.load(session.data?.user.id ?? "");
  if (
    to.params.chatId &&
    !workspace.state.chats.some(
      (chat) => chat.id === to.params.chatId && !chat.archivedAt,
    )
  )
    return landingPath(permissions);
  if (
    to.params.projectId &&
    !workspace.state.projects.some(
      (project) => project.id === to.params.projectId,
    )
  )
    return landingPath(permissions);
  const landing = landingPath(permissions);
  if (to.path === "/login" || to.path === "/") return landing;
  if (landing === "/no-access") return to.path === landing ? true : landing;
  if (to.path === "/no-access") return landing;
  const area = to.meta.area as Area | undefined;
  if (area && !hasArea(permissions, area)) return landing;
  if (
    area &&
    to.meta.menu &&
    !areaMenus(permissions, area).some(
      (menu) => menuTarget(menu, area) === to.path,
    )
  )
    return area === "backend" ? "/admin" : "/chat";
  return true;
});
