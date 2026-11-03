import { request } from "./http";
import type { Page } from "../types/api";
import type {
  AgentChatDto,
  AgentProjectDto,
  WorkspaceApi,
} from "../types/workspace";

/** 只允许正整数字符串 ID，防止本地草稿 UUID 被传入 Java Long 路径参数。 */
function resourceId(id: string): string {
  if (!/^[1-9]\d*$/.test(id)) throw new Error("资源 ID 不合法");
  return encodeURIComponent(id);
}
/** Java 默认每页 20 条，前端滚动加载仅递增 current，不自行排序服务端分页结果。 */
function query(current: number): string {
  if (!Number.isInteger(current) || current < 1 || current > 2_147_483_647)
    throw new Error("页码不合法");
  return new URLSearchParams({
    current: String(current),
    size: "20",
  }).toString();
}
const projects = "/user/agent/projects",
  chats = "/user/agent/chats";
/** 与两个 Controller 一一对应；只使用已定义的路径，分享接口尚未提供，不猜测请求地址。 */
export const workspaceApi: WorkspaceApi = {
  projects: (current) =>
    request<Page<AgentProjectDto>>(`${projects}?${query(current)}`),
  projectChats: (id, current) =>
    request<Page<AgentChatDto>>(
      `${projects}/${resourceId(id)}/chats?${query(current)}`,
    ),
  standalone: (current) =>
    request<Page<AgentChatDto>>(`${chats}/standalone?${query(current)}`),
  archived: (current) =>
    request<Page<AgentChatDto>>(`${chats}/archived?${query(current)}`),
  createProject: (projectName, description) =>
    request<AgentProjectDto>(projects, {
      method: "POST",
      body: JSON.stringify({ projectName, description }),
    }),
  updateProject: (id, projectName, description) =>
    request<string>(`${projects}/${resourceId(id)}`, {
      method: "PUT",
      body: JSON.stringify({ projectName, description }),
    }),
  deleteProject: (id) =>
    request<string>(`${projects}/${resourceId(id)}`, { method: "DELETE" }),
  // 项目 ID 以十进制字符串发送，由 Jackson 绑定 Long，避免 JS Number 丢失雪花 ID 精度。
  createChat: (chatName, projectId) => {
    if (projectId !== null) resourceId(projectId);
    return request<AgentChatDto>(chats, {
      method: "POST",
      body: JSON.stringify({ chatName, projectId }),
    });
  },
  archiveChat: (id) =>
    request<string>(`${chats}/${resourceId(id)}/archive`, { method: "PUT" }),
  restoreChat: (id) =>
    request<string>(`${chats}/${resourceId(id)}/restore`, { method: "PUT" }),
  moveChat: (id, projectId) => {
    if (projectId !== null) resourceId(projectId);
    return request<string>(`${chats}/${resourceId(id)}/move`, {
      method: "PUT",
      body: JSON.stringify({ projectId }),
    });
  },
  generateChatName: (message) =>
    request<{ chatName: string }>(`${chats}/name/generate`, {
      method: "POST",
      body: JSON.stringify({ message }),
    }),
  renameChat: (id, chatName) =>
    request<string>(`${chats}/${resourceId(id)}/name`, {
      method: "PUT",
      body: JSON.stringify({ chatName }),
    }),
  deleteChat: (id) =>
    request<string>(`${chats}/${resourceId(id)}`, { method: "DELETE" }),
};
