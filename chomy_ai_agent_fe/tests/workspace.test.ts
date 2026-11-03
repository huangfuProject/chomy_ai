import { describe, expect, it, vi } from "vitest";
import { createWorkspaceStore, hasMore } from "../src/stores/workspace";
import type {
  AgentChatDto,
  AgentProjectDto,
  WorkspaceApi,
} from "../src/types/workspace";
import type { Page } from "../src/types/api";

const project = (id = "970000000000200001"): AgentProjectDto => ({
  id,
  projectName: `项目${id}`,
  description: "原介绍",
  createdAt: "2026-11-01T08:00:00Z",
});
const chat = (
  id: string,
  projectId: string | null = null,
  archived: 0 | 1 = 0,
): AgentChatDto => ({
  id,
  projectId,
  projectName: projectId ? "项目" : null,
  chatName: `聊天${id}`,
  type: projectId ? 1 : 0,
  archived,
  createdAt: "2026-11-01T08:00:00Z",
});
const page = <T>(records: T[], current = 1, pages = 1): Page<T> => ({
  records,
  current,
  pages,
  total: records.length ? pages * 20 : 0,
  size: 20,
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
/** 注入异步 API 和内存偏好存储，测试不会连接开发者的数据库或浏览器存储。 */
function fixture(allowed = (_code: string) => true) {
  const api = {
    projects: vi.fn().mockResolvedValue(page([])),
    projectChats: vi.fn().mockResolvedValue(page([])),
    standalone: vi.fn().mockResolvedValue(page([])),
    archived: vi.fn().mockResolvedValue(page([])),
    createProject: vi.fn().mockResolvedValue(project()),
    createChat: vi.fn().mockResolvedValue(chat("51")),
    archiveChat: vi.fn().mockResolvedValue("已归档"),
    restoreChat: vi.fn().mockResolvedValue("已恢复"),
    moveChat: vi.fn().mockResolvedValue("已移动"),
    updateProject: vi.fn().mockResolvedValue("已修改"),
    deleteProject: vi.fn().mockResolvedValue("已删除"),
    renameChat: vi.fn().mockResolvedValue("已重命名"),
    deleteChat: vi.fn().mockResolvedValue("已删除"),
  } satisfies WorkspaceApi;
  const data = new Map<string, string>();
  const storage = () => ({
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
  });
  const store = createWorkspaceStore(api, allowed, storage);
  store.load("user-a");
  return { api, store, data, storage };
}

describe("服务端工作空间分页", () => {
  it("保留大整数 ID，归档条目不在普通导航显示，分页结束后不再请求", async () => {
    const { store, api } = fixture();
    api.standalone
      .mockResolvedValueOnce(
        page(
          [chat("970000000000100001"), chat("970000000000100002", null, 1)],
          1,
          2,
        ),
      )
      .mockResolvedValueOnce(
        page([chat("970000000000100001"), chat("970000000000100003")], 2, 2),
      );
    await store.loadStandalone();
    expect(hasMore(store.state.standalonePage)).toBe(true);
    await store.loadStandalone();
    await store.loadStandalone();
    expect(api.standalone.mock.calls).toEqual([[1], [2]]);
    expect(store.listStandaloneChats().map((c) => c.id)).toEqual([
      "970000000000100001",
      "970000000000100003",
    ]);
    expect(
      store.state.chats.find((c) => c.id === "970000000000100002")?.archived,
    ).toBe(1);
  });
  it("重复滚动合并同一请求，失败不推进页码且可重试", async () => {
    const { store, api } = fixture();
    const pending = deferred<Page<AgentProjectDto>>();
    api.projects.mockReturnValueOnce(pending.promise);
    const first = store.loadProjects();
    await store.loadProjects();
    expect(api.projects).toHaveBeenCalledOnce();
    pending.resolve(page([project()], 1, 2));
    await first;
    api.projects.mockRejectedValueOnce(new Error("网络失败"));
    await store.loadProjects();
    expect(store.state.projectsPage.current).toBe(1);
    expect(store.state.projectsPage.error).toBe("网络失败");
    api.projects.mockResolvedValueOnce(
      page([project("970000000000200002")], 2, 2),
    );
    await store.loadProjects();
    expect(api.projects.mock.calls).toEqual([[1], [2], [2]]);
    expect(store.state.projectsPage.error).toBe("");
  });
  it("项目聊天各有独立游标，归档列表单独分页并附带项目名", async () => {
    const { store, api } = fixture();
    api.projectChats
      .mockResolvedValueOnce(page([chat("11", "21")], 1, 2))
      .mockResolvedValueOnce(page([chat("12", "22")], 1, 1));
    await Promise.all([
      store.loadProjectChats("21"),
      store.loadProjectChats("22"),
    ]);
    expect(store.projectPage("21").pages).toBe(2);
    expect(store.projectPage("22").pages).toBe(1);
    expect(store.listProjectChats("21").map((c) => c.id)).toEqual(["11"]);
    api.archived.mockResolvedValueOnce(page([chat("13", "21", 1)]));
    await store.loadArchived();
    expect(store.listArchivedChats()[0]).toMatchObject({
      id: "13",
      projectName: "项目",
      archived: 1,
    });
    expect(store.listProjectChats("21").map((c) => c.id)).toEqual(["11"]);
  });
  it("账户切换丢弃旧请求响应，列表重置丢弃旧页响应", async () => {
    const { store, api } = fixture();
    const old = deferred<Page<AgentProjectDto>>();
    api.projects.mockReturnValueOnce(old.promise);
    const pending = store.loadProjects();
    store.load("user-b");
    old.resolve(page([project()]));
    await pending;
    expect(store.state.projects).toEqual([]);
    const stale = deferred<Page<AgentProjectDto>>();
    api.projects.mockReturnValueOnce(stale.promise);
    const request = store.loadProjects();
    api.projects.mockResolvedValueOnce(page([project("22")]));
    await store.loadProjects(true);
    stale.resolve(page([project("11")]));
    await request;
    expect(store.listProjects().map((p) => p.id)).toEqual(["22"]);
  });
  it("权限独立检查，菜单不隐式授予读取或修改权限", async () => {
    const { store, api } = fixture(() => false);
    await store.loadProjects();
    expect(api.projects).not.toHaveBeenCalled();
    expect(store.state.projectsPage.error).toContain("权限");
    await expect(store.createProject("项目", "")).rejects.toThrow("权限");
    expect(api.createProject).not.toHaveBeenCalled();
  });
  it("空页终止异常页数，未知及空归档编码不会默认为正常", async () => {
    const { store, api } = fixture();
    api.projects.mockResolvedValueOnce(page([], 1, 900));
    await store.loadProjects();
    expect(hasMore(store.state.projectsPage)).toBe(false);
    api.standalone.mockResolvedValueOnce(
      page([{ ...chat("11"), archived: null }]),
    );
    await store.loadStandalone();
    expect(store.state.standalonePage.error).toContain("格式");
    expect(store.state.chats).toEqual([]);
  });
});

describe("真实服务端写入与草稿", () => {
  it("创建项目使用服务端 ID，修改项目仅重命名时保留原介绍", async () => {
    const { store, api } = fixture();
    api.projects.mockResolvedValue(page([project()]));
    const created = await store.createProject("新项目", "新介绍");
    expect(created.id).toBe("970000000000200001");
    expect(api.createProject).toHaveBeenCalledWith("新项目", "新介绍");
    await store.updateProject(created.id, "改名");
    expect(api.updateProject).toHaveBeenCalledWith(
      created.id,
      "改名",
      "原介绍",
    );
    await store.updateProject(created.id, "改名", "");
    expect(api.updateProject).toHaveBeenLastCalledWith(
      created.id,
      "改名",
      null,
    );
  });
  it("重命名和删除仅在服务端成功后更新，失败保留原记录", async () => {
    const { store, api } = fixture();
    api.standalone.mockResolvedValue(page([chat("11")]));
    await store.loadStandalone();
    api.renameChat.mockRejectedValueOnce(new Error("无权限"));
    await expect(store.renameChat("11", "改名")).rejects.toThrow("无权限");
    expect(store.state.chats[0]?.title).toBe("聊天11");
    await store.renameChat("11", "改名");
    expect(store.state.chats[0]?.title).toBe("改名");
    api.deleteChat.mockRejectedValueOnce(new Error("服务失败"));
    await expect(store.deleteChat("11")).rejects.toThrow("服务失败");
    expect(store.state.chats).toHaveLength(1);
    api.standalone.mockResolvedValue(page([]));
    await store.deleteChat("11");
    expect(store.state.chats).toEqual([]);
    expect(api.standalone).toHaveBeenLastCalledWith(1);
  });
  it("删除项目包含缓存中的归档聊天，并重置项目及归档分页", async () => {
    const { store, api } = fixture();
    api.projects.mockResolvedValue(page([project("21")]));
    await store.loadProjects();
    api.archived.mockResolvedValue(page([chat("11", "21", 1)]));
    await store.loadArchived();
    api.projects.mockResolvedValue(page([]));
    api.archived.mockResolvedValue(page([]));
    await store.deleteProject("21");
    expect(api.deleteProject).toHaveBeenCalledWith("21");
    expect(store.state.chats).toEqual([]);
    expect(store.listProjects()).toEqual([]);
    expect(api.archived).toHaveBeenLastCalledWith(1);
  });
  it("创建聊天取得服务器 ID，刷新通过列表恢复草稿，失败不会生成本地成功记录", async () => {
    const { store, api, storage } = fixture();
    api.standalone.mockResolvedValue(page([chat("51")]));
    const created = await store.createChat(null, "本机输入");
    expect(created.id).toBe("51");
    expect(created.source).toBe("remote");
    expect(api.createChat).toHaveBeenCalledWith("本机输入", null);
    const restored = createWorkspaceStore(api, () => true, storage);
    restored.load("user-a");
    expect(restored.state.chats).toEqual([]);
    await restored.loadStandalone();
    expect(restored.listStandaloneChats()[0]?.draft).toBe("本机输入");
    api.createChat.mockRejectedValueOnce(new Error("创建失败"));
    await expect(store.createChat()).rejects.toThrow("创建失败");
    expect(store.state.chats).toHaveLength(1);
  });
  it("直接访问通过分页定位资源，其他账户的草稿不能恢复", async () => {
    const { store, api } = fixture();
    api.projects
      .mockResolvedValueOnce(page([project("21")], 1, 2))
      .mockResolvedValueOnce(page([project("22")], 2, 2));
    expect(await store.ensureProject("22")).toBe(true);
    expect(api.projects.mock.calls).toEqual([[1], [2]]);
    api.projectChats.mockResolvedValueOnce(page([chat("11", "22")]));
    expect(await store.ensureChat("11", "22")).toBe(true);
    const draft = await store.createChat();
    store.load("user-b");
    expect(store.state.chats).toEqual([]);
    expect(await store.ensureChat(draft.id, null)).toBe(false);
  });
});

describe("写入与在途读取的竞争", () => {
  it("删除成功后的迟到归档查询不会重新带回已删除聊天", async () => {
    const { store, api } = fixture();
    api.standalone.mockResolvedValue(page([chat("11")]));
    await store.loadStandalone();
    const old = deferred<Page<AgentChatDto>>();
    api.archived
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(page([]));
    const reading = store.loadArchived();
    api.standalone.mockResolvedValue(page([]));
    await store.deleteChat("11");
    old.resolve(page([chat("11", null, 1)]));
    await reading;
    expect(store.state.chats).toEqual([]);
    expect(store.listArchivedChats()).toEqual([]);
  });
  it("重命名等待期间实体被列表替换，仍更新最新缓存对象", async () => {
    const { store, api } = fixture();
    api.standalone.mockResolvedValue(page([chat("11")]));
    await store.loadStandalone();
    const write = deferred<string>();
    api.renameChat.mockReturnValueOnce(write.promise);
    const renaming = store.renameChat("11", "最终标题");
    api.archived.mockResolvedValue(page([chat("11", null, 1)]));
    await store.loadArchived();
    write.resolve("成功");
    await renaming;
    expect(store.state.chats.find((c) => c.id === "11")?.title).toBe(
      "最终标题",
    );
  });
});

describe("聊天创建、归档恢复及移动", () => {
  it("归档和恢复等待服务端成功，列表重置且归档状态保持数字编码", async () => {
    const { store, api } = fixture();
    api.standalone.mockResolvedValue(page([chat("11")]));
    await store.loadStandalone();
    api.standalone.mockResolvedValue(page([chat("11", null, 1)]));
    await store.archiveChat("11");
    expect(api.archiveChat).toHaveBeenCalledWith("11");
    expect(store.listStandaloneChats()).toEqual([]);
    api.archived.mockResolvedValue(page([chat("11", null, 1)]));
    await store.loadArchived();
    api.standalone.mockResolvedValue(page([chat("11")]));
    api.archived.mockResolvedValue(page([]));
    await store.archiveChat("11", false);
    expect(api.restoreChat).toHaveBeenCalledWith("11");
    expect(store.listStandaloneChats()[0]?.archived).toBe(0);
    expect(store.listArchivedChats()).toEqual([]);
  });
  it("归档权限不能代替恢复权限，失败不改变原数据", async () => {
    const { store, api } = fixture((code) => code !== "agent:chat:restore");
    api.standalone.mockResolvedValue(page([chat("11")]));
    await store.loadStandalone();
    api.archiveChat.mockRejectedValueOnce(new Error("归档失败"));
    await expect(store.archiveChat("11")).rejects.toThrow("归档失败");
    expect(store.listStandaloneChats()).toHaveLength(1);
    await expect(store.archiveChat("11", false)).rejects.toThrow("权限");
    expect(api.restoreChat).not.toHaveBeenCalled();
  });
  it("移动保持归档状态并刷新源、目标列表，不在请求前修改项目归属", async () => {
    const { store, api } = fixture();
    api.projects.mockResolvedValue(page([project("21")]));
    await store.loadProjects();
    api.archived.mockResolvedValue(page([chat("11", null, 1)]));
    await store.loadArchived();
    const pending = deferred<string>();
    api.moveChat.mockReturnValueOnce(pending.promise);
    const moving = store.moveChat("11", "21");
    expect(store.state.chats[0]?.projectId).toBeNull();
    api.archived.mockResolvedValue(page([chat("11", "21", 1)]));
    api.projectChats.mockResolvedValue(page([chat("11", "21", 1)]));
    pending.resolve("已移动");
    await moving;
    expect(api.moveChat).toHaveBeenCalledWith("11", "21");
    expect(store.state.chats[0]).toMatchObject({
      projectId: "21",
      archived: 1,
    });
    expect(store.listProjectChats("21")).toEqual([]);
    expect(api.standalone).toHaveBeenCalledWith(1);
  });
});
