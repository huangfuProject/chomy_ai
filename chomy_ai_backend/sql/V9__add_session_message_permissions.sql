-- Chat 级历史读取及 SSE 订阅权限，完成原 V2 初始化后手动执行；历史查询还要求先执行 V8 建消息表。
-- 执行前核对用户前台目录 970000000000200000、super_admin 角色 970000000001000002
-- 及下面固定权限和授权 ID；同编码记录采用自定义 ID 时须先调整关联值。
-- 每条 INSERT 只插入一行，按唯一索引幂等插入；其他冲突回滚，不覆盖已有自定义配置。
-- 仅显式授权超级管理员，其他角色不自动授权；执行后撤销受影响用户 Redis 会话或要求重新登录。
-- start 只是订阅，不创建消息、Task 或启动 Agent；本脚本不自动执行。
-- @author chomy
BEGIN;

-- 聊天消息历史独立授权，不因拥有聊天创建、重命名或其他读取权限而隐式开放。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200025, 970000000000200000, 'agent:chat:messages', '聊天消息历史', 3, '/message/{chatId}/messages', 'GET', 0, 150, '仅可访问本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200025, 970000000001000002, 970000000000200025, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 订阅聊天事件独立授权，不因拥有聊天创建、重命名或其他读取权限而隐式开放。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200026, 970000000000200000, 'agent:chat:subscribe', '订阅聊天事件', 3, '/message/{chatId}/start', 'GET', 0, 160, '仅可访问本人聊天，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200026, 970000000001000002, 970000000000200026, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
