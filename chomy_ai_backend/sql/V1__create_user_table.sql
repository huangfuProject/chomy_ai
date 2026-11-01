-- 用户表初始化脚本：先连接 chomy_ai 数据库，再按版本顺序执行一次。
-- 本脚本不会自动执行；由具有 public 模式建表权限的数据库账号执行。
-- 主键由应用通过 MyBatis-Plus ASSIGN_ID 生成，手动插入时须显式提供正数 ID。
-- 使用事务保证表、注释和索引同时生效；任一步失败时应回滚。
-- 仅保留主键、NOT NULL、默认值及唯一索引；非空白、格式、状态与关联关系由代码校验。
-- 不创建自定义函数或触发器，更新时间由后续业务代码维护。
BEGIN;

CREATE TABLE public.sys_user (
    id              BIGINT          NOT NULL,
    username        VARCHAR(64)     NOT NULL,
    nickname        VARCHAR(64)     NOT NULL,
    gender          SMALLINT        NOT NULL DEFAULT 0,
    phone           VARCHAR(32)     NOT NULL,
    password_hash   VARCHAR(255)    NOT NULL,
    signature       VARCHAR(255),
    avatar_url      VARCHAR(2048),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_type     SMALLINT        NOT NULL,
    created_by      BIGINT          NOT NULL,
    status          SMALLINT        NOT NULL DEFAULT 0,
    actived         SMALLINT        NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at   TIMESTAMPTZ,

    CONSTRAINT pk_sys_user PRIMARY KEY (id)
);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_user_phone ON public.sys_user (phone);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_user_username ON public.sys_user (username);

COMMENT ON TABLE public.sys_user IS '用户基础信息表；用户名和手机号分别唯一，密码仅保存不可逆哈希；业务校验由代码维护';
COMMENT ON COLUMN public.sys_user.id IS '用户主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_user.username IS '用户名，必填且全表唯一，区分大小写，不能包含首尾空格';
COMMENT ON COLUMN public.sys_user.nickname IS '昵称，必填且不为空，不能包含首尾空格，最长 64 个字符，允许重名';
COMMENT ON COLUMN public.sys_user.gender IS '性别：0 未知、1 男、2 女，必填，默认 0，合法值由业务代码校验';
COMMENT ON COLUMN public.sys_user.phone IS '手机号，必填且全表唯一；应用应在写入前统一国家码及号码格式';
COMMENT ON COLUMN public.sys_user.password_hash IS '密码密文，必填；保存带盐的不可逆密码哈希，禁止明文或可逆加密';
COMMENT ON COLUMN public.sys_user.signature IS '个性签名，可为空';
COMMENT ON COLUMN public.sys_user.avatar_url IS '头像 URL，可为空';
COMMENT ON COLUMN public.sys_user.created_at IS '创建时间，必填，默认当前事务时间，使用带时区时间戳';
COMMENT ON COLUMN public.sys_user.create_type IS '创建方式，必填：0 管理员追加，1 自注册';
COMMENT ON COLUMN public.sys_user.created_by IS '创建人 ID，必填；管理员追加填管理员 ID，自注册填自身 ID，系统初始化可填 0';
COMMENT ON COLUMN public.sys_user.status IS '用户状态，必填：0 正常，1 冻结，默认 0';
COMMENT ON COLUMN public.sys_user.actived IS '激活标记，必填：0 未激活，1 激活，默认 1；保留 actived 命名';
COMMENT ON COLUMN public.sys_user.updated_at IS '最近更新时间，必填，插入时默认当前时间，后续由业务代码更新';
COMMENT ON COLUMN public.sys_user.last_login_at IS '最近成功登录时间，未登录时为空，由登录业务在认证成功后更新';

COMMIT;
