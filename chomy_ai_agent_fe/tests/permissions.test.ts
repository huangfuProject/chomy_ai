import { describe, expect, it } from "vitest";
import {
  areaMenus,
  hasArea,
  hasPermission,
  landingPath,
  menuTarget,
} from "../src/utils/permissions";
import type { Permission } from "../src/types/api";

/** 使用大整数的字符串 ID，验证权限分区不依赖 JS 数字精度或角色推断。 */
function resource(
  id: string,
  code: string,
  type: Permission["permissionType"],
  parentId: string | null = null,
): Permission {
  return {
    id,
    permissionCode: code,
    permissionName: code,
    permissionType: type,
    parentId,
    status: 0,
    sortOrder: 0,
    routePath: null,
  };
}
const front = resource("970000000000200000", "menu:frontend", 0);
const back = resource("970000000000300000", "menu:backend", 0);
const agent = {
  ...resource("970000000000200010", "agent:manage", 1, front.id),
  routePath: "/agents",
};
const users = {
  ...resource("970000000000100000", "user:manage", 1, back.id),
  routePath: "/user-management",
};

describe("工作区授权和菜单边界", () => {
  it("无目录授权进入空白提示页，不以普通业务权限推断目录", () => {
    expect(landingPath([])).toBe("/no-access");
    expect(landingPath([users])).toBe("/no-access");
    expect(hasArea([{ ...front, status: 1 }], "frontend")).toBe(false);
  });
  it("四种授权组合选择正确首页", () => {
    expect(landingPath([front])).toBe("/chat");
    expect(landingPath([back])).toBe("/admin");
    expect(landingPath([front, back])).toBe("/chat");
  });
  it("前后台菜单隔离，根目录不隐式授予菜单或接口", () => {
    const api = resource("4", "user:get:page", 3, users.id);
    const all = [front, back, agent, users, api];
    expect(areaMenus(all, "frontend")).toEqual([agent]);
    expect(areaMenus(all, "backend")).toEqual([users]);
    expect(areaMenus([front], "frontend")).toEqual([]);
    expect(hasPermission([back, users], "user:get:page")).toBe(false);
    expect(hasPermission([api], "user:get:detail")).toBe(false);
    expect(hasPermission([api], "user:get:*")).toBe(false);
  });
  it("孤立、禁用、循环及跨区域菜单默认拒绝", () => {
    expect(areaMenus([front, users], "frontend")).toEqual([]);
    expect(areaMenus([front, { ...agent, status: 1 }], "frontend")).toEqual([]);
    expect(
      areaMenus([front, { ...agent, parentId: agent.id }], "frontend"),
    ).toEqual([]);
    expect(
      areaMenus([front, { ...agent, parentId: "missing" }], "frontend"),
    ).toEqual([]);
  });
  it("已授权未知路由仅进入占位页，不能跳转外部 URL", () => {
    expect(menuTarget(agent, "frontend")).toBe("/agents");
    expect(menuTarget(users, "backend")).toBe("/admin/user-management");
    expect(
      menuTarget({ ...users, routePath: "https://example.org" }, "backend"),
    ).toBe(`/admin/resource/${users.id}`);
  });
});
