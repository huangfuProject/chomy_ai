import { reactive } from "vue";
import { workspaceApi } from "../api/workspace";
import { session } from "./session";
import { hasPermission } from "../utils/permissions";
import type { Page } from "../types/api";
import type {
  AgentChatDto,
  AgentProjectDto,
  WorkspaceApi,
  WorkspaceChat,
  WorkspacePage,
  WorkspaceProject,
} from "../types/workspace";

/** 独立分页对象不能复用，否则一个项目翻页会改变另一个项目的加载状态。 */
export function emptyPage(): WorkspacePage {
  return {
    ids: [],
    current: 0,
    pages: 0,
    total: 0,
    initialized: false,
    loading: false,
    error: "",
    version: 0,
  };
}
export function hasMore(page: WorkspacePage): boolean {
  return !page.initialized || page.current < page.pages;
}
const preferenceKey = (id: string) => `chomy.workspace.preferences.v2:${id}`;
/** 只保存本机偏好、未发送窗口和草稿；服务器项目及对话不从旧本地快照恢复。 */
interface Preferences {
  sidebarCollapsed: boolean;
  pinned: string[];
  drafts: Record<string, string>;
  unsent: WorkspaceChat[];
}

/**
 * 服务器工作空间状态，分页、读写权限和账户隔离均在此集中处理。
 * API 与权限判断允许注入；epoch 隔离账户切换，列表 version 隔离重置前的迟到响应。
 */
export function createWorkspaceStore(
  api: WorkspaceApi = workspaceApi,
  allowed: (code: string) => boolean = (code) =>
    hasPermission(session.data?.permissions ?? [], code),
  storage: () => Pick<Storage, "getItem" | "setItem"> = () => localStorage,
) {
  const state = reactive({
    userId: "",
    projects: [] as WorkspaceProject[],
    chats: [] as WorkspaceChat[],
    sidebarCollapsed: false,
    storageWarning: "",
    projectsPage: emptyPage(),
    standalonePage: emptyPage(),
    archivedPage: emptyPage(),
    projectPages: {} as Record<string, WorkspacePage>,
  });
  let epoch = 0;
  let preferences: Preferences = {
    sidebarCollapsed: false,
    pinned: [],
    drafts: {},
    unsent: [],
  };
  function can(code: string) {
    return !!state.userId && allowed(code);
  }
  function requirePermission(code: string) {
    if (!can(code)) throw new Error("无此操作权限，请联系管理员");
  }
  function owner() {
    if (!state.userId) throw new Error("请先登录");
    return epoch;
  }
  function assertOwner(version: number) {
    if (epoch !== version || !state.userId)
      throw new Error("登录账户已变化，请重新操作");
  }
  function persist() {
    if (!state.userId) return;
    preferences.sidebarCollapsed = state.sidebarCollapsed;
    preferences.unsent = state.chats.filter((c) => c.source === "draft");
    try {
      storage().setItem(
        preferenceKey(state.userId),
        JSON.stringify(preferences),
      );
      state.storageWarning = "";
    } catch {
      state.storageWarning = "本地草稿或偏好无法保存，服务器记录不受影响。";
    }
  }
  function load(userId: string) {
    if (userId === state.userId) return;
    epoch++;
    Object.assign(state, {
      userId,
      projects: [],
      chats: [],
      sidebarCollapsed: false,
      storageWarning: "",
      projectsPage: emptyPage(),
      standalonePage: emptyPage(),
      archivedPage: emptyPage(),
      projectPages: {},
    });
    preferences = {
      sidebarCollapsed: false,
      pinned: [],
      drafts: {},
      unsent: [],
    };
    if (!userId) return;
    try {
      const raw = storage().getItem(preferenceKey(userId));
      if (!raw) return;
      const saved = JSON.parse(raw) as Preferences;
      // 本地恢复仅接收有明确草稿标记的窗口，不把旧版 UUID 项目当作服务器实体。
      if (
        typeof saved.sidebarCollapsed !== "boolean" ||
        !Array.isArray(saved.pinned) ||
        !saved.pinned.every((id) => typeof id === "string") ||
        !saved.drafts ||
        typeof saved.drafts !== "object" ||
        !Array.isArray(saved.unsent)
      )
        throw new Error("本地偏好损坏");
      preferences.sidebarCollapsed = saved.sidebarCollapsed;
      preferences.pinned = saved.pinned;
      preferences.drafts = Object.fromEntries(
        Object.entries(saved.drafts).filter(
          ([id, text]) =>
            /^[1-9]\d*$/.test(id) &&
            typeof text === "string" &&
            text.length <= 20_000,
        ),
      );
      state.sidebarCollapsed = saved.sidebarCollapsed;
      state.chats = saved.unsent.filter(
        (c) =>
          c &&
          c.source === "draft" &&
          typeof c.id === "string" &&
          c.id.startsWith("draft-") &&
          typeof c.title === "string" &&
          typeof c.draft === "string" &&
          c.draft.length <= 20_000 &&
          (c.projectId === null || typeof c.projectId === "string"),
      );
    } catch {
      state.storageWarning = "本地草稿无法恢复，请重新打开聊天窗口。";
    }
  }
  function projectPage(id: string): WorkspacePage {
    if (!state.projectPages[id]) state.projectPages[id] = emptyPage();
    return state.projectPages[id];
  }
  function mapProject(p: AgentProjectDto): WorkspaceProject {
    if (
      typeof p.id !== "string" ||
      !/^[1-9]\d*$/.test(p.id) ||
      typeof p.projectName !== "string"
    )
      throw new Error("项目响应格式不正确");
    return {
      id: p.id,
      name: p.projectName,
      description: p.description ?? "",
      createdAt: p.createdAt,
      pinned: preferences.pinned.includes(p.id),
    };
  }
  function mapChat(c: AgentChatDto): WorkspaceChat {
    if (
      typeof c.id !== "string" ||
      !/^[1-9]\d*$/.test(c.id) ||
      typeof c.chatName !== "string" ||
      (c.archived !== 0 && c.archived !== 1) ||
      (c.type !== 0 && c.type !== 1) ||
      (c.type === 0
        ? c.projectId !== null
        : typeof c.projectId !== "string" || !/^[1-9]\d*$/.test(c.projectId))
    )
      throw new Error("聊天响应格式不正确");
    return {
      id: c.id,
      title: c.chatName,
      projectId: c.projectId,
      projectName: c.projectName,
      archived: c.archived,
      source: "remote",
      createdAt: c.createdAt,
      draft: preferences.drafts[c.id] ?? "",
    };
  }
  /** 归档列表与普通列表共享实体，ID 合并后保持草稿，并去除跨页重复记录。 */
  function merge<T extends { id: string }>(target: T[], rows: T[]) {
    for (const row of rows) {
      const index = target.findIndex((r) => r.id === row.id);
      if (index < 0) target.push(row);
      else target[index] = row;
    }
  }
  /** 请求失败不推进页码，连续滚动去重；重置会使旧版本响应失效。 */
  async function fetchPage<T>(
    page: WorkspacePage,
    permission: string,
    fetcher: (current: number) => Promise<Page<T>>,
    apply: (records: T[]) => string[],
    reset = false,
  ): Promise<boolean> {
    if (!state.userId) return false;
    if (reset) {
      const version = page.version + 1;
      Object.assign(page, emptyPage(), { version });
    }
    if (page.loading || (!reset && !hasMore(page))) return false;
    if (!can(permission)) {
      page.error = "无此列表权限，请联系管理员";
      page.initialized = true;
      return false;
    }
    const account = epoch,
      version = page.version,
      next = page.current + 1;
    page.loading = true;
    page.error = "";
    try {
      const result = await fetcher(next);
      if (account !== epoch || version !== page.version) return false;
      const totalPages = Number(result.pages),
        total = Number(result.total);
      if (
        !Array.isArray(result.records) ||
        !Number.isInteger(totalPages) ||
        totalPages < 0 ||
        !Number.isFinite(total) ||
        total < 0 ||
        Number(result.current) !== next
      )
        throw new Error("分页响应格式不正确");
      const ids = apply(result.records);
      page.ids = [...new Set([...page.ids, ...ids])];
      page.current = next;
      page.total = total;
      // 空页立即终止，避免后端页数异常或数据删除后持续请求空页。
      page.pages = result.records.length ? totalPages : next;
      page.initialized = true;
      return true;
    } catch (cause) {
      if (account === epoch && version === page.version)
        page.error =
          cause instanceof Error ? cause.message : "列表加载失败，请重试";
      return false;
    } finally {
      if (account === epoch && version === page.version) page.loading = false;
    }
  }
  const loadProjects = (reset = false) =>
    fetchPage(
      state.projectsPage,
      "agent:project:page",
      api.projects,
      (records) => {
        const rows = records.map(mapProject);
        merge(state.projects, rows);
        return rows.map((p) => p.id);
      },
      reset,
    );
  const applyChats = (records: AgentChatDto[]) => {
    const rows = records.map(mapChat);
    merge(state.chats, rows);
    return rows.map((c) => c.id);
  };
  const loadStandalone = (reset = false) =>
    fetchPage(
      state.standalonePage,
      "agent:chat:standalone",
      api.standalone,
      applyChats,
      reset,
    );
  const loadArchived = (reset = false) =>
    fetchPage(
      state.archivedPage,
      "agent:chat:archived",
      api.archived,
      applyChats,
      reset,
    );
  const loadProjectChats = (id: string, reset = false) =>
    fetchPage(
      projectPage(id),
      "agent:chat:project",
      (page) => api.projectChats(id, page),
      applyChats,
      reset,
    );
  function listProjects() {
    const ids = new Set(state.projectsPage.ids);
    return state.projects
      .filter((p) => ids.has(p.id))
      .sort(
        (a, b) =>
          Number(!!b.pinned) - Number(!!a.pinned) ||
          state.projectsPage.ids.indexOf(a.id) -
            state.projectsPage.ids.indexOf(b.id),
      );
  }
  function visibleChats(page: WorkspacePage, projectId: string | null) {
    return [
      ...state.chats.filter(
        (c) => c.source === "draft" && c.projectId === projectId,
      ),
      ...page.ids.flatMap((id) => {
        const c = state.chats.find(
          (c) =>
            c.id === id &&
            c.source === "remote" &&
            c.archived === 0 &&
            c.projectId === projectId,
        );
        return c ? [c] : [];
      }),
    ];
  }
  const listStandaloneChats = () => visibleChats(state.standalonePage, null);
  const listProjectChats = (id: string) => visibleChats(projectPage(id), id);
  const listArchivedChats = () =>
    state.archivedPage.ids.flatMap((id) => {
      const c = state.chats.find((c) => c.id === id && c.archived === 1);
      return c ? [c] : [];
    });
  /** 缺少详情接口时，直接地址通过有界分页定位本人资源，不把本地缓存当作所有权证明。 */
  async function ensureProject(id: string): Promise<boolean> {
    if (state.projects.some((p) => p.id === id)) return true;
    while (hasMore(state.projectsPage)) {
      if (!(await loadProjects())) return false;
      if (state.projects.some((p) => p.id === id)) return true;
    }
    return false;
  }
  async function ensureChat(
    id: string,
    projectId: string | null,
  ): Promise<boolean> {
    const exists = () =>
      state.chats.some(
        (c) => c.id === id && c.archived === 0 && c.projectId === projectId,
      );
    if (exists()) return true;
    if (id.startsWith("draft-")) return false;
    const page = projectId ? projectPage(projectId) : state.standalonePage;
    while (hasMore(page)) {
      if (!(await (projectId ? loadProjectChats(projectId) : loadStandalone())))
        return false;
      if (exists()) return true;
    }
    return false;
  }
  /** 写入成功后使正在进行的旧查询失效，避免迟到列表重新带回已删除记录或覆盖新名称。 */
  function invalidateReads() {
    for (const page of [
      state.projectsPage,
      state.standalonePage,
      state.archivedPage,
      ...Object.values(state.projectPages),
    ]) {
      page.version++;
      page.loading = false;
    }
  }
  async function createProject(
    name: string,
    description: string,
  ): Promise<WorkspaceProject> {
    requirePermission("agent:project:create");
    const version = owner();
    if (!name.trim() || name.length > 128 || description.length > 2000)
      throw new Error("项目名称需为 1 至 128 个字符，介绍最多 2000 个字符");
    const result = await api.createProject(name.trim(), description || null);
    assertOwner(version);
    invalidateReads();
    const project = mapProject(result);
    merge(state.projects, [project]);
    // 插入和删除会改变服务端 OFFSET，重置项目分页，防止跳过或重复旧页。
    await loadProjects(true);
    assertOwner(version);
    return project;
  }
  /** 新窗口先在服务端创建，只有取得真实 ID 后才进入侧栏；旧本地草稿可在发送时升级。 */
  async function createChat(
    projectId: string | null = null,
    draft = "",
    previousDraftId?: string,
  ): Promise<WorkspaceChat> {
    requirePermission("agent:chat:create");
    const version = owner();
    if (projectId !== null && !state.projects.some((p) => p.id === projectId))
      throw new Error("项目不存在");
    if (draft.length > 20_000) throw new Error("草稿不能超过 20000 个字符");
    const result = await api.createChat(
      draft.trim().replace(/\s+/g, " ").slice(0, 24) || "新聊天",
      projectId,
    );
    assertOwner(version);
    invalidateReads();
    const chat = mapChat(result);
    chat.projectName =
      state.projects.find((p) => p.id === projectId)?.name ?? null;
    chat.draft = draft;
    preferences.drafts[chat.id] = draft;
    if (previousDraftId?.startsWith("draft-"))
      state.chats = state.chats.filter((c) => c.id !== previousDraftId);
    merge(state.chats, [chat]);
    persist();
    await (projectId
      ? loadProjectChats(projectId, true)
      : loadStandalone(true));
    assertOwner(version);
    return state.chats.find((c) => c.id === chat.id) ?? chat;
  }
  /** 归档、恢复独立鉴权；保留草稿和项目归属，只在请求成功后更新导航及归档分页。 */
  async function archiveChat(id: string, archived = true) {
    requirePermission(archived ? "agent:chat:archive" : "agent:chat:restore");
    const version = owner(),
      chat = state.chats.find((c) => c.id === id);
    if (!chat || chat.source !== "remote")
      throw new Error("请先创建服务端聊天");
    await (archived ? api.archiveChat(id) : api.restoreChat(id));
    assertOwner(version);
    const reloadArchives =
      state.archivedPage.initialized || state.archivedPage.loading;
    invalidateReads();
    const current = state.chats.find((c) => c.id === id);
    if (current) current.archived = archived ? 1 : 0;
    await Promise.all([
      chat.projectId
        ? loadProjectChats(chat.projectId, true)
        : loadStandalone(true),
      reloadArchives ? loadArchived(true) : Promise.resolve(),
    ]);
    assertOwner(version);
  }
  /** 移动保持原归档状态；源列表和目标列表均从第一页刷新，避免页偏移造成缺项。 */
  async function moveChat(id: string, projectId: string | null) {
    requirePermission("agent:chat:move");
    const version = owner(),
      chat = state.chats.find((c) => c.id === id);
    if (!chat || chat.source !== "remote")
      throw new Error("请先创建服务端聊天");
    if (projectId !== null && !state.projects.some((p) => p.id === projectId))
      throw new Error("目标项目不存在");
    const previousProjectId = chat.projectId;
    await api.moveChat(id, projectId);
    assertOwner(version);
    const reloadArchives =
      state.archivedPage.initialized || state.archivedPage.loading;
    invalidateReads();
    const current = state.chats.find((c) => c.id === id);
    if (current) {
      current.projectId = projectId;
      current.projectName =
        state.projects.find((p) => p.id === projectId)?.name ?? null;
    }
    const sources = [...new Set([previousProjectId, projectId])];
    await Promise.all([
      ...sources.map((value) =>
        value ? loadProjectChats(value, true) : loadStandalone(true),
      ),
      reloadArchives ? loadArchived(true) : Promise.resolve(),
    ]);
    assertOwner(version);
  }
  function saveDraft(id: string, draft: string, submitted = false) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) return;
    if (draft.length > 20_000) throw new Error("草稿不能超过 20000 个字符");
    chat.draft = draft;
    if (
      chat.source === "draft" &&
      submitted &&
      draft.trim() &&
      chat.title === "新聊天"
    )
      chat.title = draft.trim().replace(/\s+/g, " ").slice(0, 24);
    if (chat.source === "remote") preferences.drafts[id] = draft;
    persist();
  }
  async function renameChat(id: string, name: string) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    if (!name.trim() || name.length > 128)
      throw new Error("聊天名称需为 1 至 128 个字符");
    const version = owner();
    if (chat.source === "remote") {
      requirePermission("agent:chat:rename");
      await api.renameChat(id, name.trim());
      assertOwner(version);
    }
    invalidateReads();
    const current = state.chats.find((c) => c.id === id);
    if (current) current.title = name.trim();
    persist();
  }
  async function updateProject(id: string, name: string, description?: string) {
    requirePermission("agent:project:update");
    const version = owner(),
      project = state.projects.find((p) => p.id === id);
    if (!project) throw new Error("项目不存在");
    const text = description ?? project.description;
    if (!name.trim() || name.length > 128 || text.length > 2000)
      throw new Error("项目名称最多 128 个字符，介绍最多 2000 个字符");
    // 项目仅重命名时也传回原介绍，避免 PUT 的空介绍语义意外清空旧值。
    await api.updateProject(id, name.trim(), text || null);
    assertOwner(version);
    invalidateReads();
    const current = state.projects.find((p) => p.id === id);
    if (current) {
      current.name = name.trim();
      current.description = text;
    }
    for (const chat of state.chats)
      if (chat.projectId === id) chat.projectName = name.trim();
  }
  async function deleteChat(id: string) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    const version = owner();
    if (chat.source === "remote") {
      requirePermission("agent:chat:delete");
      await api.deleteChat(id);
      assertOwner(version);
    }
    const reloadArchives =
      state.archivedPage.initialized || state.archivedPage.loading;
    invalidateReads();
    state.chats = state.chats.filter((c) => c.id !== id);
    delete preferences.drafts[id];
    persist();
    if (chat.source === "remote") {
      await Promise.all([
        chat.projectId
          ? loadProjectChats(chat.projectId, true)
          : loadStandalone(true),
        reloadArchives ? loadArchived(true) : Promise.resolve(),
      ]);
    }
  }
  async function deleteProject(id: string) {
    requirePermission("agent:project:delete");
    const version = owner();
    await api.deleteProject(id);
    assertOwner(version);
    const reloadArchives =
      state.archivedPage.initialized || state.archivedPage.loading;
    invalidateReads();
    for (const chat of state.chats)
      if (chat.projectId === id) delete preferences.drafts[chat.id];
    state.projects = state.projects.filter((p) => p.id !== id);
    state.chats = state.chats.filter((c) => c.projectId !== id);
    delete state.projectPages[id];
    preferences.pinned = preferences.pinned.filter((value) => value !== id);
    persist();
    await Promise.all([
      loadProjects(true),
      reloadArchives ? loadArchived(true) : Promise.resolve(),
    ]);
  }
  function toggleProjectPin(id: string) {
    const p = state.projects.find((p) => p.id === id);
    if (!p) return;
    p.pinned = !p.pinned;
    preferences.pinned = p.pinned
      ? [...new Set([...preferences.pinned, id])]
      : preferences.pinned.filter((value) => value !== id);
    persist();
  }
  function toggleSidebar() {
    state.sidebarCollapsed = !state.sidebarCollapsed;
    persist();
  }
  return {
    state,
    can,
    load,
    projectPage,
    loadProjects,
    loadStandalone,
    loadArchived,
    loadProjectChats,
    listProjects,
    listStandaloneChats,
    listProjectChats,
    listArchivedChats,
    ensureProject,
    ensureChat,
    createProject,
    createChat,
    archiveChat,
    moveChat,
    saveDraft,
    renameChat,
    updateProject,
    deleteChat,
    deleteProject,
    toggleProjectPin,
    toggleSidebar,
  };
}
export const workspace = createWorkspaceStore();
