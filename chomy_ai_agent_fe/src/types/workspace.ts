import type { Page } from "./api";

/** 后端项目字段原样定义；雪花 ID 始终使用字符串，禁止转换为 Number。 */
export interface AgentProjectDto {
  id: string;
  projectName: string;
  description: string | null;
  createdAt: string;
}
/** 归属与归档为严格数字枚举，不能把空值当成正常状态。 */
export interface AgentChatDto {
  id: string;
  projectId: string | null;
  projectName?: string | null;
  chatName: string;
  type: 0 | 1;
  archived: 0 | 1;
  createdAt: string;
}
export interface WorkspaceProject {
  id: string;
  name: string;
  description: string;
  createdAt: string;
  pinned?: boolean;
}
/** draft 只表示未发送的本地窗口，不能将其 UUID 传给 Java 的 Long 参数。 */
export interface WorkspaceChat {
  id: string;
  projectId: string | null;
  projectName?: string | null;
  title: string;
  draft: string;
  archived: 0 | 1;
  source: "remote" | "draft";
  createdAt: string;
}
/** 每个列表独立保留页码、实体 ID、请求状态和重试信息，避免项目间共用分页游标。 */
export interface WorkspacePage {
  ids: string[];
  current: number;
  pages: number;
  total: number;
  initialized: boolean;
  loading: boolean;
  error: string;
  version: number;
}
/** 可注入的真实 HTTP 边界；测试只替换该边界，不使用生产模拟数据。 */
export interface WorkspaceApi {
  projects(current: number): Promise<Page<AgentProjectDto>>;
  projectChats(projectId: string, current: number): Promise<Page<AgentChatDto>>;
  standalone(current: number): Promise<Page<AgentChatDto>>;
  archived(current: number): Promise<Page<AgentChatDto>>;
  createProject(
    projectName: string,
    description: string | null,
  ): Promise<AgentProjectDto>;
  updateProject(
    id: string,
    projectName: string,
    description: string | null,
  ): Promise<string>;
  deleteProject(id: string): Promise<string>;
  createChat(chatName: string, projectId: string | null): Promise<AgentChatDto>;
  archiveChat(id: string): Promise<string>;
  restoreChat(id: string): Promise<string>;
  moveChat(id: string, projectId: string | null): Promise<string>;
  renameChat(id: string, chatName: string): Promise<string>;
  deleteChat(id: string): Promise<string>;
}
