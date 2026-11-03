-- 初始化数据脚本：连接 chomy_ai，并在 V1__create_rbac_tables.sql 建表成功后手动执行。
-- 仅插入目录、菜单、接口/按钮权限、管理员、超级管理员角色及其显式授权关系，不修改表结构。
-- 执行账号须具有业务表的必要读写权限；项目不会在启动时自动执行本脚本。
-- 先插入全部权限及管理员、角色，最后统一将全部权限授予超级管理员角色。
-- 使用固定种子 ID 和唯一索引幂等插入，不覆盖已有账户密码、状态或自定义配置。
-- 所有关联直接填写固定种子 ID；已有同编码记录时，执行前须确认其 ID 与本脚本一致。
-- 主键或接口索引发生其他冲突时整个事务回滚；执行前应确认同名账户及角色的身份。
-- 密码仅保留 BCrypt 哈希，不保存明文；普通测试用户不纳入版本 SQL。
-- 超级管理员通过角色显式获得全部权限；后续新增权限也须同步写入角色权限关系，不在代码中绕过鉴权。
-- @author chomy
BEGIN;

-- 第一部分：导航目录、用户管理菜单及九个接口/操作按钮共用权限。
-- 一、两个顶层菜单大类使用现有目录类型 0，目录只负责导航分组，不配置页面或接口路径。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, status, sort_order, description, created_by)
VALUES
    (970000000000200000, NULL, 'menu:frontend', '用户前台', 0, 0, 10, '用户前台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, status, sort_order, description, created_by)
VALUES
    (970000000000300000, NULL, 'menu:backend', '用户后台', 0, 0, 20, '用户后台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 二、页面菜单使用类型 1，parent_id 直接填写用户后台目录的固定种子 ID。
-- 用户前台暂不设置子菜单，用户后台包含用户管理；拥有菜单不等于拥有相应业务接口权限。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, route_path, status, sort_order, description, created_by)
VALUES
    (970000000000100000, 970000000000300000, 'user:manage', '用户管理', 1, '/user-management', 0, 10, '后台用户管理菜单，操作按各自接口权限码授权', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 三、每个接口节点直接归属菜单，同时承担相应操作按钮的前端权限判断。
-- permission_type=3 保持兼容已有接口匹配规则，接口路径、方法及权限码不变。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100011, 970000000000100000, 'user:update:create', '新建用户', 3, '/admin/users', 'POST', 0, 10, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100012, 970000000000100000, 'user:update:activate', '激活用户', 3, '/admin/users/{id}/activate', 'PUT', 0, 20, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100013, 970000000000100000, 'user:update:freeze', '冻结用户', 3, '/admin/users/{id}/freeze', 'PUT', 0, 30, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100014, 970000000000100000, 'user:update:unfreeze', '解冻用户', 3, '/admin/users/{id}/unfreeze', 'PUT', 0, 40, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100015, 970000000000100000, 'user:update:password', '修改用户密码', 3, '/admin/users/{id}/password', 'PUT', 0, 50, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100016, 970000000000100000, 'user:update:info', '修改用户信息', 3, '/admin/users/{id}', 'PUT', 0, 60, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100017, 970000000000100000, 'user:update:delete', '删除用户', 3, '/admin/users/{id}', 'DELETE', 0, 70, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100031, 970000000000100000, 'user:get:detail', '查看用户详情', 3, '/admin/users/{id}', 'GET', 0, 80, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000100032, 970000000000100000, 'user:get:page', '分页查询用户', 3, '/admin/users', 'GET', 0, 90, '接口与操作按钮共用此权限码，无需另外分配按钮权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 第二部分：管理员、超级管理员角色及用户角色和角色权限关联。
-- 一、管理员默认正常且已激活，昵称为超级管理员、性别为男，联系方式留空。
INSERT INTO public.sys_user
    (id, username, nickname, gender, phone, email, password_hash, create_type, created_by, status, actived)
VALUES
    (970000000001000001, 'admin', '超级管理员', 1, NULL, NULL, '$2b$12$XoCCcQIGyS1O41g42KsQyOCB/89/MAyPX0BZPa1Joi9D/Ks0U7.5O', 0, 0, 0, 1)
ON CONFLICT (username) DO NOTHING;

-- 二、独立角色承载全部权限，业务代码仍逐项检查实际接口权限。
INSERT INTO public.sys_role (id, role_code, role_name, description, status, sort_order, created_by)
VALUES
    (970000000001000002, 'super_admin', '超级管理员', '拥有全部权限，后续新增权限由版本脚本同步授予', 0, 0, 0)
ON CONFLICT (role_code) DO NOTHING;

-- 三、管理员与角色直接使用上方固定种子 ID 关联，已有同名记录须预先核对 ID。
INSERT INTO public.sys_user_role (id, user_id, role_id, created_by)
VALUES
    (970000000001000003, 970000000001000001, 970000000001000002, 0)
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 第三部分：项目及对话独立接口权限，直接归属用户前台目录，不新建菜单。
-- 先插入全部接口节点，再由下方统一授权语句将其加入超级管理员角色。
-- 权限与操作按钮共用类型 3 节点；其他角色仍须单独授权，执行后须撤销受影响用户旧 Redis 会话。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200011, 970000000000200000, 'agent:project:create', '创建项目', 3, '/user/agent/projects', 'POST', 0, 10, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200012, 970000000000200000, 'agent:project:delete', '删除项目及对话', 3, '/user/agent/projects/{projectId}', 'DELETE', 0, 20, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200013, 970000000000200000, 'agent:chat:project', '项目对话分页', 3, '/user/agent/projects/{projectId}/chats', 'GET', 0, 30, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200014, 970000000000200000, 'agent:chat:standalone', '非项目对话分页', 3, '/user/agent/chats/standalone', 'GET', 0, 40, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200015, 970000000000200000, 'agent:chat:archived', '归档对话分页', 3, '/user/agent/chats/archived', 'GET', 0, 50, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200016, 970000000000200000, 'agent:chat:delete', '删除对话', 3, '/user/agent/chats/{chatId}', 'DELETE', 0, 60, '仅可操作本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200017, 970000000000200000, 'agent:project:page', '项目分页', 3, '/user/agent/projects', 'GET', 0, 70, '仅可查看本人全部项目，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 修改项目与重命名对话独立授权，每条 INSERT 只插入一行。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200018, 970000000000200000, 'agent:project:update', '修改项目', 3, '/user/agent/projects/{projectId}', 'PUT', 0, 80, '仅可修改本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200019, 970000000000200000, 'agent:chat:rename', '重命名对话', 3, '/user/agent/chats/{chatId}/name', 'PUT', 0, 90, '仅可修改本人数据，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 将本版本全部 21 个权限逐条授予超级管理员，包括目录、菜单和接口/操作按钮。
-- role_id 固定为超级管理员种子 ID；后续每新增一个权限，必须同步追加一条独立的授权 INSERT 语句。
-- 关联 ID 沿用权限 ID 加固定偏移后的确定值，直接列出便于核对；重复关系由唯一索引幂等跳过。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200000, 970000000001000002, 970000000000200000, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000300000, 970000000001000002, 970000000000300000, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100000, 970000000001000002, 970000000000100000, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100011, 970000000001000002, 970000000000100011, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100012, 970000000001000002, 970000000000100012, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100013, 970000000001000002, 970000000000100013, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100014, 970000000001000002, 970000000000100014, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100015, 970000000001000002, 970000000000100015, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100016, 970000000001000002, 970000000000100016, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100017, 970000000001000002, 970000000000100017, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100031, 970000000001000002, 970000000000100031, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000100032, 970000000001000002, 970000000000100032, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200011, 970000000001000002, 970000000000200011, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200012, 970000000001000002, 970000000000200012, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200013, 970000000001000002, 970000000000200013, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200014, 970000000001000002, 970000000000200014, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200015, 970000000001000002, 970000000000200015, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200016, 970000000001000002, 970000000000200016, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200017, 970000000001000002, 970000000000200017, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 新增修改权限同步逐条授予固定种子超级管理员角色。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200018, 970000000001000002, 970000000000200018, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200019, 970000000001000002, 970000000000200019, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;

-- 共初始化 2 个目录、1 个菜单、18 个接口/操作权限；目录和菜单不隐式授予子权限。
-- 已登录用户须撤销旧 Redis 会话或重新登录，以读取新的角色与权限快照。
