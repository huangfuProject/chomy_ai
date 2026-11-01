import { request } from "./http";
import type { AdminUser, Page } from "../types/api";

/** 页码从 1 开始，每页固定 20 条；URLSearchParams 对查询输入编码，不拼接原始文本。 */
export function getUsers(current: number, username: string) {
  const query = new URLSearchParams({ current: String(current), size: "20" });
  if (username.trim()) query.set("username", username.trim());
  return request<Page<AdminUser>>(`/admin/users?${query}`);
}
/** 用户详情与分页拥有独立接口权限，界面必须分别检查。 */
export function getUser(id: string) {
  return request<AdminUser>(`/admin/users/${encodeURIComponent(id)}`);
}
