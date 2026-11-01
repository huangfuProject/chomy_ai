import type { Area, Permission } from "../types/api";

/** 顶层目录只决定工作区访问权，绝不隐式赋予任何子菜单或操作权限。 */
export function hasArea(permissions: Permission[], area: Area): boolean {
  return permissions.some(
    (p) =>
      p.status === 0 &&
      p.permissionType === 0 &&
      p.permissionCode === `menu:${area}`,
  );
}
/** 每个按钮按完整权限码检查，禁止使用前缀、角色名或通配符推断授权。 */
export function hasPermission(
  permissions: Permission[],
  code: string,
): boolean {
  return permissions.some((p) => p.status === 0 && p.permissionCode === code);
}
/**
 * 从已授权资源构建当前工作区菜单，接口和按钮节点永不出现在导航中。
 * 父链缺失、循环或跨目录的资源默认不显示；导航不会补发任何权限。
 */
export function areaMenus(permissions: Permission[], area: Area): Permission[] {
  if (!hasArea(permissions, area)) return [];
  const enabled = permissions.filter((p) => p.status === 0);
  const byId = new Map(enabled.map((p) => [p.id, p]));
  return enabled
    .filter((p) => {
      if (p.permissionType !== 1) return false;
      const visited = new Set<string>([p.id]);
      let parent = p.parentId;
      while (parent) {
        if (visited.has(parent)) return false;
        visited.add(parent);
        const node = byId.get(parent);
        if (!node) return false;
        if (node.permissionType === 0 && node.permissionCode === `menu:${area}`)
          return true;
        parent = node.parentId;
      }
      return false;
    })
    .sort(
      (a, b) =>
        (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.id.localeCompare(b.id),
    );
}
/** 仅将已实现的种子路由映射到页面，其他授权菜单进入占位页，禁止执行任意 URL。 */
export function menuTarget(menu: Permission, area: Area): string {
  if (
    area === "frontend" &&
    menu.permissionCode === "agent:manage" &&
    menu.routePath === "/agents"
  )
    return "/agents";
  if (
    area === "backend" &&
    menu.permissionCode === "user:manage" &&
    menu.routePath === "/user-management"
  )
    return "/admin/user-management";
  return `${area === "backend" ? "/admin" : "/front"}/resource/${encodeURIComponent(menu.id)}`;
}
export function landingPath(permissions: Permission[]): string {
  if (hasArea(permissions, "frontend")) return "/chat";
  if (hasArea(permissions, "backend")) return "/admin";
  return "/no-access";
}
