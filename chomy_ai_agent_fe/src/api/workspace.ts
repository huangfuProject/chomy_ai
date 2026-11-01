import type {
  WorkspaceChat,
  WorkspaceProject,
  WorkspaceRepository,
  WorkspaceSnapshot,
} from "../types/workspace";

const STORAGE_PREFIX = "chomy.workspace.v1:";
/** 空记录每次创建新数组，避免不同用户之间共享可变数据。 */
export function emptyWorkspace(): WorkspaceSnapshot {
  return { projects: [], chats: [], sidebarCollapsed: false };
}
/** 严格校验本地记录，损坏数据不能被当作正常项目或聊天使用。 */
function isProject(value: unknown): value is WorkspaceProject {
  if (!value || typeof value !== "object") return false;
  const p = value as WorkspaceProject;
  return (
    typeof p.id === "string" &&
    /^[\w-]+$/.test(p.id) &&
    typeof p.name === "string" &&
    !!p.name.trim() &&
    p.name.length <= 64 &&
    typeof p.description === "string" &&
    p.description.length <= 500 &&
    (p.pinned === undefined || typeof p.pinned === "boolean") &&
    typeof p.createdAt === "string"
  );
}
function isChat(value: unknown): value is WorkspaceChat {
  if (!value || typeof value !== "object") return false;
  const c = value as WorkspaceChat;
  return (
    typeof c.id === "string" &&
    /^[\w-]+$/.test(c.id) &&
    (c.projectId === null || typeof c.projectId === "string") &&
    typeof c.title === "string" &&
    typeof c.draft === "string" &&
    c.draft.length <= 20_000 &&
    (c.archivedAt == null || typeof c.archivedAt === "string") &&
    typeof c.createdAt === "string"
  );
}
/**
 * 浏览器本地适配器，仅用于后端接口提供前的交互。
 * storage 按需获取，允许测试注入内存存储，也能捕获浏览器禁用存储的异常。
 */
export function createLocalWorkspaceRepository(
  storage: () => Pick<Storage, "getItem" | "setItem"> = () => localStorage,
): WorkspaceRepository {
  return {
    load(userId) {
      const raw = storage().getItem(`${STORAGE_PREFIX}${userId}`);
      if (!raw) return emptyWorkspace();
      const parsed = JSON.parse(raw) as WorkspaceSnapshot;
      if (
        !parsed ||
        !Array.isArray(parsed.projects) ||
        !parsed.projects.every(isProject) ||
        !Array.isArray(parsed.chats) ||
        !parsed.chats.every(isChat) ||
        typeof parsed.sidebarCollapsed !== "boolean"
      )
        throw new Error("本地工作空间记录格式不正确");
      // 项目必须存在且 ID 唯一，孤立聊天和重复 ID 不进入导航。
      const projectIds = new Set(parsed.projects.map((p) => p.id));
      if (
        projectIds.size !== parsed.projects.length ||
        new Set(parsed.chats.map((c) => c.id)).size !== parsed.chats.length ||
        parsed.chats.some(
          (c) => c.projectId !== null && !projectIds.has(c.projectId),
        )
      )
        throw new Error("本地工作空间关联不完整");
      return parsed;
    },
    save(userId, snapshot) {
      storage().setItem(`${STORAGE_PREFIX}${userId}`, JSON.stringify(snapshot));
    },
    // 三种列表保持独立，不提前假定接口路径、分页格式或后端字段。
    listProjects: (snapshot) =>
      [...snapshot.projects].sort(
        (a, b) => Number(!!b.pinned) - Number(!!a.pinned),
      ),
    listProjectChats: (snapshot, projectId) =>
      snapshot.chats.filter((c) => c.projectId === projectId && !c.archivedAt),
    listStandaloneChats: (snapshot) =>
      snapshot.chats.filter((c) => c.projectId === null && !c.archivedAt),
  };
}
