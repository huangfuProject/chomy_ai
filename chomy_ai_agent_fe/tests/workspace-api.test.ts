import { beforeEach, describe, expect, it, vi } from "vitest";
import { workspaceApi } from "../src/api/workspace";
import { request } from "../src/api/http";

vi.mock("../src/api/http", () => ({ request: vi.fn().mockResolvedValue({}) }));
beforeEach(() => vi.mocked(request).mockClear());
/** 对照 Java Controller 校验路径、方法及 DTO 字段，防止继续使用本地字段名或猜测 URL。 */
describe("项目与聊天 HTTP 合约", () => {
  it("四类列表使用独立路径与 current/size 分页", async () => {
    await workspaceApi.projects(2);
    await workspaceApi.standalone(3);
    await workspaceApi.projectChats("970000000000000001", 4);
    await workspaceApi.archived(5);
    expect(vi.mocked(request).mock.calls.map((c) => c[0])).toEqual([
      "/user/agent/projects?current=2&size=20",
      "/user/agent/chats/standalone?current=3&size=20",
      "/user/agent/projects/970000000000000001/chats?current=4&size=20",
      "/user/agent/chats/archived?current=5&size=20",
    ]);
  });
  it("写操作使用后端白名单字段，不发送用户 ID、项目所有者或 UI 状态", async () => {
    await workspaceApi.createProject("项目", null);
    await workspaceApi.updateProject("21", "修改", "介绍");
    await workspaceApi.renameChat("11", "新名");
    await workspaceApi.deleteChat("11");
    await workspaceApi.deleteProject("21");
    expect(vi.mocked(request).mock.calls).toEqual([
      [
        "/user/agent/projects",
        {
          method: "POST",
          body: JSON.stringify({ projectName: "项目", description: null }),
        },
      ],
      [
        "/user/agent/projects/21",
        {
          method: "PUT",
          body: JSON.stringify({ projectName: "修改", description: "介绍" }),
        },
      ],
      [
        "/user/agent/chats/11/name",
        { method: "PUT", body: JSON.stringify({ chatName: "新名" }) },
      ],
      ["/user/agent/chats/11", { method: "DELETE" }],
      ["/user/agent/projects/21", { method: "DELETE" }],
    ]);
  });
  it("聊天创建、归档、恢复和移动采用精确的 Controller 合约", async () => {
    await workspaceApi.createChat("名称", null);
    await workspaceApi.archiveChat("11");
    await workspaceApi.restoreChat("11");
    await workspaceApi.moveChat("11", "970000000000000001");
    await workspaceApi.moveChat("11", null);
    expect(vi.mocked(request).mock.calls).toEqual([
      [
        "/user/agent/chats",
        {
          method: "POST",
          body: JSON.stringify({ chatName: "名称", projectId: null }),
        },
      ],
      ["/user/agent/chats/11/archive", { method: "PUT" }],
      ["/user/agent/chats/11/restore", { method: "PUT" }],
      [
        "/user/agent/chats/11/move",
        {
          method: "PUT",
          body: JSON.stringify({ projectId: "970000000000000001" }),
        },
      ],
      [
        "/user/agent/chats/11/move",
        { method: "PUT", body: JSON.stringify({ projectId: null }) },
      ],
    ]);
  });
  it("名称生成只发送 message，不把预览结果误当成已重命名", async () => {
    await workspaceApi.generateChatName("第一次提交的内容");
    expect(request).toHaveBeenCalledExactlyOnceWith(
      "/user/agent/chats/name/generate",
      { method: "POST", body: JSON.stringify({ message: "第一次提交的内容" }) },
    );
  });
  it("草稿 UUID 和无效页码在请求发出前拒绝", () => {
    expect(() => workspaceApi.deleteChat("draft-local")).toThrow();
    expect(() => workspaceApi.projects(0)).toThrow();
    expect(() => workspaceApi.projects(1.5)).toThrow();
    expect(request).not.toHaveBeenCalled();
  });
});
