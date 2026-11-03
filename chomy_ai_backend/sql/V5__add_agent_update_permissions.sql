-- 已有数据库修改项目与重命名对话权限升级，在原 V2 或 V3 完成初始化后手动执行。
-- 若尚未执行 V4，仍须单独执行 V4 获得项目分页权限；本文件仅新增两个修改权限。
-- 执行前核对用户前台目录 970000000000200000、super_admin 角色 970000000001000002
-- 以及下面权限、授权主键与已有固定种子一致；自定义 ID 必须先调整关联值。
-- 每条 INSERT 只插入一行；按唯一索引幂等插入，不覆盖已有自定义配置，其他冲突使事务回滚。
-- 仅显式授权超级管理员，其他角色不自动授权；接口和操作按钮共用类型 3 权限节点。
-- 执行后须撤销受影响用户 Redis 会话或要求重新登录，刷新权限快照。
-- 新库 V2 已包含这些权限，不必额外执行本文件；应用不会自动执行本脚本。
-- @author chomy
BEGIN;

-- 两个修改接口使用独立权限，不通过菜单或其他读写权限隐式授权。
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

-- 固定种子超级管理员逐条持久化授权，不在业务代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200018, 970000000001000002, 970000000000200018, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200019, 970000000001000002, 970000000000200019, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
