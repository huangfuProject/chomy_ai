-- 用户管理菜单及接口/按钮共用权限初始化，须先执行 V1、V2、V3。
-- 每个操作只保留一个权限码：后台接口鉴权和前端按钮显隐都检查该码，不再单独创建 user:button:*。
-- 新建、激活、冻结、解冻、改密、改资料、删除属于 user:update:*；详情和分页属于 user:get:*。
-- 固定 ID 沿用历史接口 ID，保留已有接口授权，不创建角色、默认管理员或自动角色分配。
-- 按 permission_code 幂等插入，不覆盖已有状态或自定义配置；主键或接口索引冲突则事务失败。
-- 已执行旧版 V4 的数据库还须执行 V5 合并历史按钮，新的初始化也可以顺序执行 V5。
-- @author chomy
BEGIN;

-- 一、用户管理菜单只负责导航；拥有菜单不等于拥有所有用户管理操作。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, route_path, status, sort_order, description, created_by)
VALUES
    (970000000000100000, NULL, 'user:manage', '用户管理', 1, '/user-management', 0, 10, '后台用户管理菜单，操作按各自接口权限码授权', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 二、每个接口节点直接归属菜单，同时承担相应操作按钮的前端权限判断。
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
WHERE menu.permission_code = 'user:manage'
ON CONFLICT (permission_code) DO NOTHING;

COMMIT;

-- 最终结构：1 个菜单、9 个接口/操作共用节点，不存在额外 7 个按钮节点。
-- 前端示例：permissions 中包含 user:update:create 即显示创建按钮；后端同样校验 user:update:create。
-- 仅有 user:manage 菜单仍不能调用任何操作；本脚本不新增角色权限关联。
