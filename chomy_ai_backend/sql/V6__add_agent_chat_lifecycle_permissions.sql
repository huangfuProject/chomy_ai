-- 聊天创建、归档、恢复和移动权限的独立脚本；新库和已有库均须在 V2 后手动执行。
-- 已有库须已具备项目及聊天表；缺失时先执行 V3。V4/V5 的权限仍按需单独执行。
-- 不回写历史初始化脚本，本文件独立维护新增四个接口权限及超级管理员授权。
-- 执行前核对用户前台目录 970000000000200000 和 super_admin 角色 970000000001000002
-- 以及以下固定权限、授权主键；已有同编码记录必须核对 ID，自定义 ID 须先调整关联值。
-- 每条 INSERT 仅插入一行；唯一索引处理重复关系，不覆盖既有配置，其他冲突使事务回滚。
-- 仅授予超级管理员；其他角色不自动授权。执行后撤销受影响用户 Redis 会话或要求重新登录。
-- 接口与前端操作按钮共用类型 3 权限，不存在菜单继承或前缀隐式授权。应用不会自动执行本文件。
-- @author chomy
BEGIN;

-- 创建聊天使用独立权限，仅可操作本人数据。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200020, 970000000000200000, 'agent:chat:create', '创建聊天', 3, '/user/agent/chats', 'POST', 0, 100, '仅可操作本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 逐条显式授予固定种子超级管理员角色，不在代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200020, 970000000001000002, 970000000000200020, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 归档聊天使用独立权限，仅可操作本人数据。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200021, 970000000000200000, 'agent:chat:archive', '归档聊天', 3, '/user/agent/chats/{chatId}/archive', 'PUT', 0, 110, '仅可操作本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 逐条显式授予固定种子超级管理员角色，不在代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200021, 970000000001000002, 970000000000200021, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 恢复聊天使用独立权限，仅可操作本人数据。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200022, 970000000000200000, 'agent:chat:restore', '恢复聊天', 3, '/user/agent/chats/{chatId}/restore', 'PUT', 0, 120, '仅可操作本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 逐条显式授予固定种子超级管理员角色，不在代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200022, 970000000001000002, 970000000000200022, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 移动聊天使用独立权限，仅可操作本人数据。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200023, 970000000000200000, 'agent:chat:move', '移动聊天', 3, '/user/agent/chats/{chatId}/move', 'PUT', 0, 130, '仅可操作本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 逐条显式授予固定种子超级管理员角色，不在代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200023, 970000000001000002, 970000000000200023, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
