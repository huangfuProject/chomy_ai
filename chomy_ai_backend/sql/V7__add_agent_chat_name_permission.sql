-- 聊天名称生成独立权限脚本；新库及已有库均须在 V2 后手动执行。
-- 执行前核对用户前台目录 970000000000200000、super_admin 角色 970000000001000002
-- 以及下列权限、授权 ID 与已有固定种子一致；同编码记录使用自定义 ID 时先调整关联值。
-- 每条 INSERT 只插入一行，按唯一索引幂等插入，不覆盖已有自定义配置，其他冲突使事务回滚。
-- 仅显式授予超级管理员，其他角色不自动授权；执行后撤销受影响用户 Redis 会话或要求重新登录。
-- 当前名称生成是文本截取占位，不调用模型、不写聊天数据；后续模型接入复用同一权限。
-- @author chomy
BEGIN;

-- 名称生成独立于聊天创建及重命名权限，接口与操作按钮共用类型 3 节点。
INSERT INTO public.sys_permission
    (id, parent_id, permission_code, permission_name, permission_type, api_path, http_method, status, sort_order, description, created_by)
VALUES
    (970000000000200024, 970000000000200000, 'agent:chat:generate-name', '生成聊天名称', 3, '/user/agent/chats/name/generate', 'POST', 0, 140, '根据前端文本生成聊天名称预览，接口和操作按钮共用此权限码', 0)
ON CONFLICT (permission_code) DO NOTHING;

-- 新增权限同步逐条授予固定种子超级管理员角色，不在业务代码中绕过 RBAC。
INSERT INTO public.sys_role_permission (id, role_id, permission_id, created_by)
VALUES
    (971000000000200024, 970000000001000002, 970000000000200024, 0)
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMIT;
