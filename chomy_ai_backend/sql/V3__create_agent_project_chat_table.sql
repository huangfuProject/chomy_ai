-- 已有数据库手动升级专用：先确认项目表、对话表尚不存在，并已执行原 V1/V2。
-- 本文件不属于 V1/V2 新库初始化顺序，已有库仅执行本文件一次；禁止重复执行全量 V1。
-- 新增两张表及六个权限，并向已有 super_admin 角色补齐全部权限；不创建管理员，不自动执行。
-- 账号须具有 public 模式建表及 sys_permission 读写权限；所有步骤使用同一事务。
-- 执行前须已初始化 super_admin 角色；升级后须撤销受影响用户 Redis 会话或重新登录。
-- @author chomy
BEGIN;

-- 六、用户项目：名称允许重复，只通过应用生成的 ID 标识项目。
CREATE TABLE public.sys_agent_project (
    id              BIGINT          NOT NULL,
    project_name    VARCHAR(128)    NOT NULL,
    description     VARCHAR(2000),
    user_id         BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_agent_project PRIMARY KEY (id)
);
COMMENT ON TABLE public.sys_agent_project IS '用户项目表；项目名称允许重复，所有权及删除清理由业务代码维护';
COMMENT ON COLUMN public.sys_agent_project.id IS '项目主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_agent_project.project_name IS '项目名，必填，最长 128 个字符，允许重复';
COMMENT ON COLUMN public.sys_agent_project.description IS '项目介绍，可空，最长 2000 个字符';
COMMENT ON COLUMN public.sys_agent_project.user_id IS '所属用户 ID，来自已验签 JWT，不接受客户端指定';
COMMENT ON COLUMN public.sys_agent_project.created_at IS '创建时间，默认当前事务时间，带时区';
-- 支持按所属用户读取项目，时间相同时通过 ID 保证稳定排序。
CREATE INDEX idx_sys_agent_project_user_created ON public.sys_agent_project (user_id, created_at DESC, id DESC);

-- 七、用户对话：不建立外键或 CHECK；项目归属类型与项目 ID 的一致性由业务校验。
-- 类型 0 时 project_id 必须为空；类型 1 时必须关联属于同一用户的有效项目。
CREATE TABLE public.sys_agent_chat (
    id              BIGINT          NOT NULL,
    project_id      BIGINT,
    chat_name       VARCHAR(128)    NOT NULL,
    type            SMALLINT        NOT NULL DEFAULT 0,
    user_id         BIGINT          NOT NULL,
    archived        SMALLINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_sys_agent_chat PRIMARY KEY (id)
);
COMMENT ON TABLE public.sys_agent_chat IS '用户对话表；名称允许重复，项目删除时由业务事务删除全部关联对话';
COMMENT ON COLUMN public.sys_agent_chat.id IS '对话主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_agent_chat.project_id IS '所属项目 ID，可空；类型 1 时必填，必须与对话属于同一用户';
COMMENT ON COLUMN public.sys_agent_chat.chat_name IS '对话名称，必填，最长 128 个字符，允许重复';
COMMENT ON COLUMN public.sys_agent_chat.type IS '归属类型：0 无项目归属，1 有项目归属，默认 0；由 AgentChatType 映射';
COMMENT ON COLUMN public.sys_agent_chat.user_id IS '所属用户 ID，来自已验签 JWT，读取及删除按用户隔离';
COMMENT ON COLUMN public.sys_agent_chat.archived IS '归档状态：0 否，1 是，默认 0；由 AgentChatArchiveStatus 映射';
COMMENT ON COLUMN public.sys_agent_chat.created_at IS '创建时间，默认当前事务时间，带时区';
-- 项目对话及非项目对话的有界分页索引，第一列确保用户隔离查询有效利用索引。
CREATE INDEX idx_sys_agent_chat_user_project_created ON public.sys_agent_chat (user_id, project_id, created_at DESC, id DESC);
-- 归档列表跨项目查询，独立支持所属用户、归档标记和稳定倒序。
CREATE INDEX idx_sys_agent_chat_user_archive_created ON public.sys_agent_chat (user_id, archived, created_at DESC, id DESC);
-- 项目删除按 project_id 清理全部对话，避免全表扫描；不依赖数据库级联。
CREATE INDEX idx_sys_agent_chat_project ON public.sys_agent_chat (project_id);

-- 第三部分：项目及对话独立接口权限，直接归属用户前台目录，不新建菜单。
-- 先插入全部接口节点，再由下方统一授权语句将其加入超级管理员角色。
-- 权限与操作按钮共用类型 3 节点；其他角色仍须单独授权，执行后须撤销受影响用户旧 Redis 会话。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
SELECT seed.id, category.id, seed.code, seed.name, 3, seed.path, seed.method, 0, seed.sort_order, '仅可操作本人数据，接口和操作按钮共用此权限码', 0
FROM (VALUES
    (970000000000200011, 'agent:project:create', '创建项目', '/user/agent/projects', 'POST', 10),
    (970000000000200012, 'agent:project:delete', '删除项目及对话', '/user/agent/projects/{projectId}', 'DELETE', 20),
    (970000000000200013, 'agent:chat:project', '项目对话分页', '/user/agent/projects/{projectId}/chats', 'GET', 30),
    (970000000000200014, 'agent:chat:standalone', '非项目对话分页', '/user/agent/chats/standalone', 'GET', 40),
    (970000000000200015, 'agent:chat:archived', '归档对话分页', '/user/agent/chats/archived', 'GET', 50),
    (970000000000200016, 'agent:chat:delete', '删除对话', '/user/agent/chats/{chatId}', 'DELETE', 60)
) AS seed(id, code, name, path, method, sort_order)
JOIN public.sys_permission AS category ON category.permission_code = 'menu:frontend' AND category.permission_type = 0
ON CONFLICT (permission_code) DO NOTHING;

-- 统一将权限表中的全部权限显式授予超级管理员角色，包括本次及后续脚本新增的权限。
-- 通过角色码读取实际角色 ID，不给其他角色或用户直接增加授权，不在代码中绕过 RBAC。
-- 关联 ID 使用权限 ID 加固定偏移，重复执行不生成重复关系；其他主键冲突则事务回滚。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
SELECT permission.id + 1000000000000000, role.id, permission.id, 0
FROM public.sys_permission AS permission CROSS JOIN public.sys_role AS role
WHERE role.role_code = 'super_admin'
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_permission AS relation
                  WHERE relation.role_id = role.id AND relation.permission_id = permission.id)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
