/** 项目在前端的统一结构；真实接口接入时在适配层转换后端字段。 */
export interface WorkspaceProject {
  id: string;
  name: string;
  description: string;
  /** 项目置顶偏好；旧版本地记录缺省为未置顶。 */
  pinned?: boolean;
  createdAt: string;
}

/** 聊天窗口与草稿，projectId=null 表示独立聊天，不伪造模型回复。 */
export interface WorkspaceChat {
  id: string;
  projectId: string | null;
  title: string;
  draft: string;
  /** 归档时间为空或缺省表示未归档，兼容已有本地记录。 */
  archivedAt?: string | null;
  createdAt: string;
}

/** 本地临时数据以账号 ID 隔离；该结构不包含密码、令牌或角色权限。 */
export interface WorkspaceSnapshot {
  projects: WorkspaceProject[];
  chats: WorkspaceChat[];
  sidebarCollapsed: boolean;
}

/**
 * 统一保留三种列表入口：项目、项目聊天、无项目聊天。
 * 当前采用本地适配器；接口协议明确后替换实现，不需要改动侧栏与聊天视图。
 */
export interface WorkspaceRepository {
  load(userId: string): WorkspaceSnapshot;
  save(userId: string, snapshot: WorkspaceSnapshot): void;
  listProjects(snapshot: WorkspaceSnapshot): WorkspaceProject[];
  listProjectChats(
    snapshot: WorkspaceSnapshot,
    projectId: string,
  ): WorkspaceChat[];
  listStandaloneChats(snapshot: WorkspaceSnapshot): WorkspaceChat[];
}
