-- 兼容旧版 V4：合并用户管理的独立按钮与接口权限，不创建函数、触发器、外键或 CHECK。
-- 前提：已执行 V4 且存在 user:manage 菜单。重复执行安全，新版 V4 初始化后也可执行。
-- 只保留接口 ID、权限码及原有角色接口授权，不把“仅按钮授权”升级成接口授权，避免权限扩大。
-- 接口和按钮显隐从此共用接口权限码；菜单本身不授予操作权限。
-- @author chomy
BEGIN;

-- 一、将原按钮下的子节点直接移到用户管理菜单；包含已有自定义子节点，不删除它们。
UPDATE public.sys_permission AS child
SET parent_id = menu.id, updated_at = CURRENT_TIMESTAMP
FROM public.sys_permission AS button, public.sys_permission AS menu
WHERE child.parent_id = button.id
  AND menu.permission_code = 'user:manage' AND menu.permission_type = 1
  AND button.permission_type = 2
  AND button.permission_code IN ('user:button:create', 'user:button:activate', 'user:button:freeze', 'user:button:unfreeze', 'user:button:password', 'user:button:info', 'user:button:delete');

-- 二、明确将九个共用接口归属菜单，保留 ID、状态、路径和请求方法，仅更新展示名称和说明。
UPDATE public.sys_permission AS operation
SET parent_id = menu.id, permission_name = seed.name,
    description = '接口与操作按钮共用此权限码，无需另外分配按钮权限', updated_at = CURRENT_TIMESTAMP
FROM (VALUES
    ('user:update:create', '新建用户'),
    ('user:update:activate', '激活用户'),
    ('user:update:freeze', '冻结用户'),
    ('user:update:unfreeze', '解冻用户'),
    ('user:update:password', '修改用户密码'),
    ('user:update:info', '修改用户信息'),
    ('user:update:delete', '删除用户'),
    ('user:get:detail', '查看用户详情'),
    ('user:get:page', '分页查询用户')
) AS seed(code, name), public.sys_permission AS menu
WHERE operation.permission_code = seed.code AND operation.permission_type = 3
  AND menu.permission_code = 'user:manage' AND menu.permission_type = 1;

-- 三、清理历史按钮的角色关联。只拥有旧按钮的角色不会因此自动获得接口调用能力。
DELETE FROM public.sys_role_permission AS relation
USING public.sys_permission AS button
WHERE relation.permission_id = button.id AND button.permission_type = 2
  AND button.permission_code IN ('user:button:create', 'user:button:activate', 'user:button:freeze', 'user:button:unfreeze', 'user:button:password', 'user:button:info', 'user:button:delete')
  AND EXISTS (SELECT 1 FROM public.sys_permission WHERE permission_code = 'user:manage' AND permission_type = 1);

-- 四、删除已合并的七个旧按钮节点；对应接口节点和它们的角色关联均保持原 ID 不变。
DELETE FROM public.sys_permission AS button
WHERE button.permission_type = 2
  AND button.permission_code IN ('user:button:create', 'user:button:activate', 'user:button:freeze', 'user:button:unfreeze', 'user:button:password', 'user:button:info', 'user:button:delete')
  AND EXISTS (SELECT 1 FROM public.sys_permission WHERE permission_code = 'user:manage' AND permission_type = 1);

COMMENT ON COLUMN public.sys_permission.permission_type IS '权限类型：0 目录、1 菜单、2 历史独立按钮、3 接口及操作按钮共用权限；新接口操作不单独建按钮节点';
COMMIT;

-- 执行后须撤销受影响用户的旧 Redis 会话并重新登录，刷新菜单树与权限快照。
-- 角色原有接口授权不会丢失；只有历史按钮而没有接口的角色需管理员显式补充所需接口权限。
-- 本脚本不自动操作 Redis、不为任何角色增加授权，不影响其他模块的独立按钮类型。
