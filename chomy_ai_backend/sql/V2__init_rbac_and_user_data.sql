-- 初始化数据脚本：连接 chomy_ai，并在 V1__create_rbac_tables.sql 建表成功后手动执行。
-- 仅插入目录、菜单、接口/按钮权限、管理员、超级管理员角色及其显式授权关系，不修改表结构。
-- 执行账号须具有业务表的必要读写权限；项目不会在启动时自动执行本脚本。
-- 先插入全部权限及管理员、角色，最后统一将全部权限授予超级管理员角色。
-- 使用固定种子 ID 和唯一索引幂等插入，不覆盖已有账户密码、状态或自定义配置。
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
    (970000000000200000, NULL, 'menu:frontend', '用户前台', 0, 0, 10, '用户前台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0),
    (970000000000300000, NULL, 'menu:backend', '用户后台', 0, 0, 20, '用户后台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 二、页面菜单使用类型 1，通过目录权限码查找实际父 ID，不依赖目录必须采用固定种子 ID。
-- 用户前台暂不设置子菜单，用户后台包含用户管理；拥有菜单不等于拥有相应业务接口权限。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, route_path, status, sort_order, description, created_by)
SELECT seed.id, category.id, seed.code, seed.name, 1, seed.route, 0, 10, seed.description, 0
FROM (VALUES
    (970000000000100000, 'menu:backend', 'user:manage', '用户管理', '/user-management', '后台用户管理菜单，操作按各自接口权限码授权')
) AS seed(id, category_code, code, name, route, description)
JOIN public.sys_permission AS category ON category.permission_code = seed.category_code AND category.permission_type = 0
ON CONFLICT (permission_code) DO NOTHING;

-- 三、每个接口节点直接归属菜单，同时承担相应操作按钮的前端权限判断。
-- permission_type=3 保持兼容已有接口匹配规则，接口路径、方法及权限码不变。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
SELECT seed.id, menu.id, seed.code, seed.name, 3, seed.path, seed.method, 0, seed.sort_order, seed.description, 0
FROM (VALUES
    (970000000000100011, 'user:update:create', '新建用户', '/admin/users', 'POST', 10, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100012, 'user:update:activate', '激活用户', '/admin/users/{id}/activate', 'PUT', 20, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100013, 'user:update:freeze', '冻结用户', '/admin/users/{id}/freeze', 'PUT', 30, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100014, 'user:update:unfreeze', '解冻用户', '/admin/users/{id}/unfreeze', 'PUT', 40, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100015, 'user:update:password', '修改用户密码', '/admin/users/{id}/password', 'PUT', 50, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100016, 'user:update:info', '修改用户信息', '/admin/users/{id}', 'PUT', 60, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100017, 'user:update:delete', '删除用户', '/admin/users/{id}', 'DELETE', 70, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100031, 'user:get:detail', '查看用户详情', '/admin/users/{id}', 'GET', 80, '接口与操作按钮共用此权限码，无需另外分配按钮权限'),
    (970000000000100032, 'user:get:page', '分页查询用户', '/admin/users', 'GET', 90, '接口与操作按钮共用此权限码，无需另外分配按钮权限')
) AS seed(id, code, name, path, method, sort_order, description)
CROSS JOIN public.sys_permission menu
WHERE menu.permission_code = 'user:manage' AND menu.permission_type = 1
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
VALUES (970000000001000002, 'super_admin', '超级管理员', '拥有全部权限，后续新增权限由版本脚本同步授予', 0, 0, 0)
ON CONFLICT (role_code) DO NOTHING;

-- 三、通过用户名和角色码查询实际 ID，避免重复执行或已存在记录时依赖固定主键。
INSERT INTO public.sys_user_role (id, user_id, role_id, created_by)
SELECT 970000000001000003, account.id, role.id, 0
FROM public.sys_user AS account CROSS JOIN public.sys_role AS role
WHERE account.username = 'admin' AND role.role_code = 'super_admin'
ON CONFLICT (user_id, role_id) DO NOTHING;

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

-- 共初始化 2 个目录、1 个菜单、15 个接口/操作权限；目录和菜单不隐式授予子权限。
-- 已登录用户须撤销旧 Redis 会话或重新登录，以读取新的角色与权限快照。
