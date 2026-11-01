import { reactive } from "vue";
import {
  createLocalWorkspaceRepository,
  emptyWorkspace,
} from "../api/workspace";
import type {
  WorkspaceChat,
  WorkspaceProject,
  WorkspaceRepository,
  WorkspaceSnapshot,
} from "../types/workspace";

/**
 * 项目与聊天交互状态；仓储可替换，以便后续分别对接三类列表接口。
 * userId 是数据隔离边界；切换用户先清空内存，再加载该用户自己的记录。
 */
export function createWorkspaceStore(
  repository: WorkspaceRepository = createLocalWorkspaceRepository(),
) {
  const state = reactive({
    ...emptyWorkspace(),
    userId: "",
    storageWarning: "",
  });

  /** 本地保存失败时仍保留当前窗口，向用户说明刷新后可能无法恢复。 */
  function persist() {
    if (!state.userId) return;
    const snapshot: WorkspaceSnapshot = {
      projects: state.projects,
      chats: state.chats,
      sidebarCollapsed: state.sidebarCollapsed,
    };
    try {
      repository.save(state.userId, snapshot);
      state.storageWarning = "";
    } catch {
      state.storageWarning = "本地记录暂时无法保存，刷新页面后可能丢失。";
    }
  }
  function load(userId: string) {
    if (userId === state.userId) return;
    Object.assign(state, emptyWorkspace(), { userId, storageWarning: "" });
    if (!userId) return;
    try {
      Object.assign(state, repository.load(userId));
    } catch {
      state.storageWarning = "无法读取本地记录，当前使用临时工作空间。";
    }
  }
  /** 项目名必填，介绍允许留空；在状态层重复校验，避免绕过表单产生空目录。 */
  function createProject(name: string, description: string): WorkspaceProject {
    if (!state.userId) throw new Error("请先登录");
    const normalized = name.trim();
    if (!normalized || normalized.length > 64)
      throw new Error("项目名需为 1 至 64 个字符");
    if (description.trim().length > 500)
      throw new Error("项目介绍不能超过 500 个字符");
    const project = {
      id: crypto.randomUUID(),
      name: normalized,
      description: description.trim(),
      createdAt: new Date().toISOString(),
    };
    state.projects.unshift(project);
    persist();
    return project;
  }
  /** projectId 为 null 创建独立聊天；项目不存在时拒绝创建孤立窗口。 */
  function createChat(
    projectId: string | null = null,
    draft = "",
  ): WorkspaceChat {
    if (!state.userId) throw new Error("请先登录");
    if (projectId !== null && !state.projects.some((p) => p.id === projectId))
      throw new Error("项目不存在");
    if (draft.length > 20_000) throw new Error("聊天草稿不能超过 20000 个字符");
    const chat = {
      id: crypto.randomUUID(),
      projectId,
      title: "新聊天",
      draft,
      createdAt: new Date().toISOString(),
    };
    state.chats.unshift(chat);
    persist();
    return chat;
  }
  /** 草稿绑定唯一窗口；只有显式提交时才从输入内容生成标题，避免多个空窗口混淆。 */
  function saveDraft(id: string, draft: string, submitted = false) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    if (draft.length > 20_000) throw new Error("聊天草稿不能超过 20000 个字符");
    chat.draft = draft;
    if (submitted && draft.trim() && chat.title === "新聊天")
      chat.title = draft.trim().replace(/\s+/g, " ").slice(0, 24);
    persist();
  }
  /** 重命名不改动草稿与项目归属；名称在状态层再次校验。 */
  function renameChat(id: string, title: string) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    if (!title.trim() || title.trim().length > 100)
      throw new Error("聊天名称需为 1 至 100 个字符");
    chat.title = title.trim();
    persist();
  }
  /** 归档只隐藏导航，不删除内容；恢复后仍回到原项目或独立列表。 */
  function archiveChat(id: string, archived = true) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    chat.archivedAt = archived ? new Date().toISOString() : null;
    persist();
  }
  /** 移动前校验目标项目，null 表示从项目移出成为独立聊天。 */
  function moveChat(id: string, projectId: string | null) {
    const chat = state.chats.find((c) => c.id === id);
    if (!chat) throw new Error("聊天不存在");
    if (projectId !== null && !state.projects.some((p) => p.id === projectId))
      throw new Error("目标项目不存在");
    chat.projectId = projectId;
    persist();
  }
  function deleteChat(id: string) {
    if (!state.chats.some((c) => c.id === id)) throw new Error("聊天不存在");
    state.chats = state.chats.filter((c) => c.id !== id);
    persist();
  }
  /** 修改项目资料只接受名称和介绍，不影响其下聊天记录。 */
  function updateProject(id: string, name: string, description?: string) {
    const project = state.projects.find((p) => p.id === id);
    if (!project) throw new Error("项目不存在");
    if (!name.trim() || name.trim().length > 64)
      throw new Error("项目名需为 1 至 64 个字符");
    if (description !== undefined && description.trim().length > 500)
      throw new Error("项目介绍不能超过 500 个字符");
    project.name = name.trim();
    if (description !== undefined) project.description = description.trim();
    persist();
  }
  function toggleProjectPin(id: string) {
    const project = state.projects.find((p) => p.id === id);
    if (!project) throw new Error("项目不存在");
    project.pinned = !project.pinned;
    persist();
  }
  /** 项目删除在界面明确确认后执行，同时清理所属聊天（包含归档记录），不留下孤立关系。 */
  function deleteProject(id: string) {
    if (!state.projects.some((p) => p.id === id)) throw new Error("项目不存在");
    state.chats = state.chats.filter((c) => c.projectId !== id);
    state.projects = state.projects.filter((p) => p.id !== id);
    persist();
  }
  function toggleSidebar() {
    state.sidebarCollapsed = !state.sidebarCollapsed;
    persist();
  }
  return {
    state,
    load,
    createProject,
    createChat,
    saveDraft,
    toggleSidebar,
    renameChat,
    archiveChat,
    moveChat,
    deleteChat,
    updateProject,
    toggleProjectPin,
    deleteProject,
    listProjects: () => repository.listProjects(state),
    listProjectChats: (id: string) => repository.listProjectChats(state, id),
    listStandaloneChats: () => repository.listStandaloneChats(state),
  };
}
export const workspace = createWorkspaceStore();
