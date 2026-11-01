-- 用户前台、用户后台目录及菜单和接口/按钮共用权限初始化，须先执行 V1、V2、V3。
-- 每个操作只保留一个权限码：后台接口鉴权和前端按钮显隐都检查该码，不再单独创建 user:button:*。
-- 新建、激活、冻结、解冻、改密、改资料、删除属于 user:update:*；详情和分页属于 user:get:*。
-- 使用固定种子 ID，不创建角色、默认管理员或自动角色分配。
-- 按 permission_code 幂等插入，不覆盖已有状态或自定义配置；主键或接口索引冲突则事务失败。
-- 本脚本一次性初始化最终导航结构，不需要额外的菜单迁移或历史按钮清理脚本。
-- @author chomy
BEGIN;

-- 一、两个顶层菜单大类使用现有目录类型 0，目录只负责导航分组，不配置页面或接口路径。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, status, sort_order, description, created_by)
VALUES
    (970000000000200000, NULL, 'menu:frontend', '用户前台', 0, 0, 10, '用户前台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0),
    (970000000000300000, NULL, 'menu:backend', '用户后台', 0, 0, 20, '用户后台菜单大类，仅组织导航，不隐式授予子菜单或接口权限', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 二、页面菜单使用类型 1，通过目录权限码查找实际父 ID，不依赖目录必须采用固定种子 ID。
-- 用户前台包含智能体，用户后台包含用户管理；拥有菜单不等于拥有相应业务接口权限。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, route_path, status, sort_order, description, created_by)
SELECT seed.id, category.id, seed.code, seed.name, 1, seed.route, 0, 10, seed.description, 0
FROM (VALUES
    (970000000000200010, 'menu:frontend', 'agent:manage', '智能体', '/agents', '用户前台智能体菜单，仅表示页面导航权限，不隐式授予业务接口权限'),
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

COMMIT;

-- 最终结构：2 个顶层目录、2 个菜单、9 个接口/操作共用节点，共 13 个权限资源。
-- 用户前台 -> 智能体；用户后台 -> 用户管理 -> 九个接口/操作按钮共用权限。
-- 前端示例：permissions 中包含 user:update:create 即显示创建按钮；后端同样校验 user:update:create。
-- 仅有 user:manage 菜单仍不能调用任何操作；本脚本不新增角色权限关联。
-- 智能体菜单只定义前端路由，不创建尚未实现的业务接口或按钮权限。
-- 前端可补齐已授权子菜单的祖先目录用于展示，但展示祖先不等于授予其权限。
-- 本项目不自动执行 SQL；执行后须显式授权，已有登录用户需刷新 Redis 会话或重新登录。
