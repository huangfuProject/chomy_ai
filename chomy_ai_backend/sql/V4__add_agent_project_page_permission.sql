-- 已有数据库项目分页权限升级：在原 V2 或 V3 已完成初始化后手动执行。
-- 执行前必须核对用户前台目录 970000000000200000、super_admin 角色
-- 970000000001000002 及下列权限、授权主键与既有固定种子一致，避免关联到错误记录。
-- 仅新增类型 3 的接口/操作按钮共用节点，并显式授权超级管理员；其他角色不自动授权。
-- 按编码及角色权限唯一索引幂等插入，不覆盖已有权限配置；主键冲突会回滚事务。
-- 执行后须撤销受影响用户的 Redis 会话或要求重新登录，以刷新缓存中的权限快照。
-- 新库 V2 已包含此权限，无需额外执行本文件；本脚本不由应用自动执行。
-- @author chomy
BEGIN;

-- GET 项目分页与 POST 创建项目使用不同权限，目录和创建权限不隐式授予读取权限。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200017, 970000000000200000, 'agent:project:page', '项目分页', 3, '/user/agent/projects', 'GET', 0, 70, '仅可查看本人全部项目，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 使用固定角色及权限种子 ID 显式授权，不在代码中绕过 RBAC，也不扫描其他权限。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200017, 970000000001000002, 970000000000200017, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
