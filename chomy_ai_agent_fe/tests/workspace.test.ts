import { describe, expect, it } from "vitest";
import { createLocalWorkspaceRepository } from "../src/api/workspace";
import { createWorkspaceStore } from "../src/stores/workspace";

/** 每个测试注入独立内存存储，不触碰开发者浏览器中的项目或聊天记录。 */
function fixture() {
  const data = new Map<string, string>();
  const repository = createLocalWorkspaceRepository(() => ({
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => {
      data.set(key, value);
    },
  }));
  return { data, repository, store: createWorkspaceStore(repository) };
}

describe("项目和聊天状态", () => {
  it("三种列表分离，项目聊天不混入独立聊天或其他项目", () => {
    const { store } = fixture();
    store.load("user-a");
    const first = store.createProject("研究项目", "项目介绍"),
      second = store.createProject("另一个项目", "");
    const plain = store.createChat(),
      nested = store.createChat(first.id);
    expect(store.listProjects()).toHaveLength(2);
    expect(store.listStandaloneChats().map((c) => c.id)).toEqual([plain.id]);
    expect(store.listProjectChats(first.id).map((c) => c.id)).toEqual([
      nested.id,
    ]);
    expect(store.listProjectChats(second.id)).toEqual([]);
  });
  it("多窗口草稿独立，刷新后恢复项目关联和折叠偏好", () => {
    const { store, repository } = fixture();
    store.load("user-a");
    const project = store.createProject("测试", "说明"),
      a = store.createChat(),
      b = store.createChat(project.id);
    store.saveDraft(a.id, "独立窗口的草稿", true);
    store.saveDraft(b.id, "项目窗口的草稿");
    store.toggleSidebar();
    const restored = createWorkspaceStore(repository);
    restored.load("user-a");
    expect(restored.state.chats.find((c) => c.id === a.id)).toMatchObject({
      title: "独立窗口的草稿",
      draft: "独立窗口的草稿",
      projectId: null,
    });
    expect(restored.listProjectChats(project.id)[0]).toMatchObject({
      id: b.id,
      title: "新聊天",
      draft: "项目窗口的草稿",
    });
    expect(restored.state.sidebarCollapsed).toBe(true);
  });
  it("切换账户清空前一用户的内存记录，原用户再次登录可恢复自己的项目", () => {
    const { store } = fixture();
    store.load("user-a");
    const project = store.createProject("私有项目", "");
    store.createChat(project.id);
    store.load("user-b");
    expect(store.state.projects).toEqual([]);
    expect(store.state.chats).toEqual([]);
    store.createChat();
    store.load("user-a");
    expect(store.state.projects[0]?.id).toBe(project.id);
    expect(store.listStandaloneChats()).toEqual([]);
    store.load("");
    expect(store.state.projects).toEqual([]);
    expect(() => store.createChat()).toThrow("请先登录");
  });
  it("空项目名、长度超限及无效父项目均不能创建记录", () => {
    const { store } = fixture();
    store.load("user-a");
    expect(() => store.createProject("   ", "")).toThrow();
    expect(() => store.createProject("a".repeat(65), "")).toThrow();
    expect(() => store.createProject("项目", "a".repeat(501))).toThrow();
    expect(() => store.createChat("unknown")).toThrow("项目不存在");
    expect(store.state.projects).toEqual([]);
    expect(store.state.chats).toEqual([]);
  });
  it("损坏和关联缺失的存储不能作为合法列表载入", () => {
    const { data, store } = fixture();
    data.set("chomy.workspace.v1:user-a", "{bad-json");
    store.load("user-a");
    expect(store.state.projects).toEqual([]);
    expect(store.state.storageWarning).not.toBe("");
    data.set(
      "chomy.workspace.v1:user-b",
      JSON.stringify({
        projects: [],
        chats: [
          {
            id: "chat",
            projectId: "missing",
            title: "孤立聊天",
            draft: "",
            createdAt: "",
          },
        ],
        sidebarCollapsed: false,
      }),
    );
    store.load("user-b");
    expect(store.state.chats).toEqual([]);
    expect(store.state.storageWarning).not.toBe("");
  });
  it("浏览器拒绝写入时保留当前窗口并提示，不伪报持久化成功", () => {
    const repository = createLocalWorkspaceRepository(() => ({
      getItem: () => null,
      setItem: () => {
        throw new Error("quota");
      },
    }));
    const store = createWorkspaceStore(repository);
    store.load("user-a");
    const chat = store.createChat();
    expect(store.state.chats[0]?.id).toBe(chat.id);
    expect(store.state.storageWarning).toContain("无法保存");
  });
});

/** 操作级回归：生命周期动作须同步导航数据，永久删除不能留下孤立的项目聊天。 */
describe("聊天及项目操作", () => {
  it("重命名与移动保留草稿，未知项目和空名称不允许更新", () => {
    const { store } = fixture();
    store.load("user-a");
    const project = store.createProject("项目", ""),
      chat = store.createChat(null, "原始草稿");
    store.renameChat(chat.id, "新标题");
    store.moveChat(chat.id, project.id);
    expect(store.listStandaloneChats()).toHaveLength(0);
    expect(store.listProjectChats(project.id)[0]).toMatchObject({
      title: "新标题",
      draft: "原始草稿",
    });
    expect(() => store.moveChat(chat.id, "missing")).toThrow();
    expect(() => store.renameChat(chat.id, "  ")).toThrow();
    store.moveChat(chat.id, null);
    expect(store.listStandaloneChats()[0]?.id).toBe(chat.id);
  });
  it("归档隐藏条目但保留内容，恢复后回到所属项目，刷新保持归档状态", () => {
    const { store, repository } = fixture();
    store.load("user-a");
    const project = store.createProject("项目", ""),
      chat = store.createChat(project.id, "保留内容");
    store.archiveChat(chat.id);
    expect(store.listProjectChats(project.id)).toEqual([]);
    const restored = createWorkspaceStore(repository);
    restored.load("user-a");
    expect(restored.state.chats[0]?.archivedAt).toBeTruthy();
    restored.archiveChat(chat.id, false);
    expect(restored.listProjectChats(project.id)[0]).toMatchObject({
      draft: "保留内容",
      projectId: project.id,
    });
  });
  it("项目置顶、设置和取消置顶正确保存，不改变聊天内容", () => {
    const { store } = fixture();
    store.load("user-a");
    const first = store.createProject("先创建", ""),
      second = store.createProject("后创建", "");
    store.toggleProjectPin(first.id);
    expect(store.listProjects()[0]?.id).toBe(first.id);
    store.updateProject(first.id, "改名", "新介绍");
    expect(store.listProjects()[0]).toMatchObject({
      name: "改名",
      description: "新介绍",
    });
    store.toggleProjectPin(first.id);
    expect(store.listProjects()[0]?.id).toBe(second.id);
  });
  it("永久删除项目清理其下普通和归档聊天，其他项目及独立聊天不受影响", () => {
    const { store, repository } = fixture();
    store.load("user-a");
    const project = store.createProject("待删除", ""),
      other = store.createProject("保留", "");
    const plain = store.createChat(),
      nested = store.createChat(project.id),
      retained = store.createChat(other.id);
    store.archiveChat(nested.id);
    store.deleteProject(project.id);
    expect(store.state.chats.map((c) => c.id).sort()).toEqual(
      [plain.id, retained.id].sort(),
    );
    store.deleteChat(plain.id);
    const restored = createWorkspaceStore(repository);
    restored.load("user-a");
    expect(restored.state.chats.map((c) => c.id)).toEqual([retained.id]);
    expect(restored.state.projects.map((p) => p.id)).toEqual([other.id]);
  });
});
