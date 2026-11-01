-- 已执行旧版 V1 的数据库通过本脚本追加性别字段；新版 V1 已含该字段，可安全跳过重复追加。
-- 只修改表结构和注释，不创建函数、触发器、外键或 CHECK，取值合法性由代码校验。
-- @author chomy
BEGIN;
ALTER TABLE public.sys_user ADD COLUMN IF NOT EXISTS gender SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN public.sys_user.gender IS '性别：0 未知、1 男、2 女，必填，默认 0，合法值由业务代码校验';
COMMIT;
