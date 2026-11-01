-- 全量建表脚本：先执行 V0 建库，再连接 chomy_ai 数据库手动执行本脚本一次。
-- 适用于空库初始化，统一创建用户及 RBAC 的五张表；已有数据库不能重复执行本脚本。
-- 性别、可空手机号和邮箱均直接采用最终字段定义，不再执行历史 ALTER TABLE 脚本。
-- 执行账号须具有 public 模式建表权限；项目不会在启动时自动执行 SQL。
-- 主键由应用通过 MyBatis-Plus ASSIGN_ID 生成，手工写入时须显式提供正数 ID。
-- 时间字段使用 TIMESTAMPTZ；审计操作者为 0 时表示系统操作，更新时间由业务代码维护。
-- 仅定义表、字段、注释和索引，保留主键、NOT NULL 和默认值；唯一性通过唯一索引保障。
-- 不创建函数、触发器、存储过程、外键、CHECK 或级联操作，关联关系及业务校验由代码负责。
-- 使用单一事务保证全部表结构同时生效；初始化数据另见 V2__init_rbac_and_user_data.sql。
-- @author chomy
BEGIN;

-- 一、用户基础信息：手机号可空且提供时唯一，邮箱可空且不要求唯一。
CREATE TABLE public.sys_user (
    id              BIGINT          NOT NULL,
    username        VARCHAR(64)     NOT NULL,
    nickname        VARCHAR(64)     NOT NULL,
    gender          SMALLINT        NOT NULL DEFAULT 0,
    phone           VARCHAR(32),
    email           VARCHAR(254),
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
COMMENT ON COLUMN public.sys_user.username IS '用户名，必填且全表唯一，仅允许英文字母、数字和下划线，格式由后端校验';
COMMENT ON COLUMN public.sys_user.nickname IS '昵称，必填且不为空，不能包含首尾空格，最长 64 个字符，允许重名';
COMMENT ON COLUMN public.sys_user.gender IS '性别：0 未知、1 男、2 女，必填，默认 0，合法值由业务代码校验';
COMMENT ON COLUMN public.sys_user.phone IS '手机号，可为空，提供时全表唯一；空字符串由业务规范为 NULL';
COMMENT ON COLUMN public.sys_user.email IS '邮箱，非必填，最长 254 个字符，格式由后端校验，不要求唯一';
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

-- 二、角色：角色编码唯一，用于稳定的权限判断；展示名称可以重复。
CREATE TABLE public.sys_role (
    id              BIGINT          NOT NULL,
    role_code       VARCHAR(64)     NOT NULL,
    role_name       VARCHAR(64)     NOT NULL,
    description     VARCHAR(500),
    status          SMALLINT        NOT NULL DEFAULT 0,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT          NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,

    CONSTRAINT pk_sys_role PRIMARY KEY (id)
);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_role_code ON public.sys_role (role_code);

COMMENT ON TABLE public.sys_role IS 'RBAC 角色表；一个用户可拥有多个角色，角色权限取并集';
COMMENT ON COLUMN public.sys_role.id IS '角色主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_role.role_code IS '角色编码，必填且全表唯一，区分大小写，例如 admin、user';
COMMENT ON COLUMN public.sys_role.role_name IS '角色中文名称，必填，允许重名';
COMMENT ON COLUMN public.sys_role.description IS '角色用途说明，可为空';
COMMENT ON COLUMN public.sys_role.status IS '状态：0 正常、1 禁用，默认 0；禁用角色不参与鉴权';
COMMENT ON COLUMN public.sys_role.sort_order IS '显示顺序，非负数，数值越小越靠前，默认 0';
COMMENT ON COLUMN public.sys_role.created_at IS '创建时间，数据库默认当前事务时间';
COMMENT ON COLUMN public.sys_role.created_by IS '创建人 ID，必填，系统初始化填 0';
COMMENT ON COLUMN public.sys_role.updated_at IS '最近更新时间，插入时默认当前时间，后续由业务代码更新';
COMMENT ON COLUMN public.sys_role.updated_by IS '最近修改人 ID，可为空，系统修改填 0，由业务维护';

-- 三、权限：目录、菜单、按钮、接口使用统一权限码，树形关系只负责组织展示。
-- 顶层 parent_id 使用 NULL；父节点有效性、自引用、多层循环和父子类型规则由代码校验。
-- 不定义父级授权继承：授予目录或菜单不会自动授予其子权限，后端应逐项校验权限码。
CREATE TABLE public.sys_permission (
    id              BIGINT          NOT NULL,
    parent_id       BIGINT,
    permission_code VARCHAR(128)    NOT NULL,
    permission_name VARCHAR(64)     NOT NULL,
    permission_type SMALLINT        NOT NULL,
    route_path      VARCHAR(255),
    api_path        VARCHAR(255),
    http_method     VARCHAR(10),
    icon            VARCHAR(128),
    status          SMALLINT        NOT NULL DEFAULT 0,
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    description     VARCHAR(500),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT          NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,

    CONSTRAINT pk_sys_permission PRIMARY KEY (id)
);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_permission_code ON public.sys_permission (permission_code);

COMMENT ON TABLE public.sys_permission IS 'RBAC 权限资源表；父子结构仅组织展示，不隐式继承授权';
COMMENT ON COLUMN public.sys_permission.id IS '权限主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_permission.parent_id IS '父权限 ID，可为空，NULL 表示顶层节点；父子合法性由代码校验';
COMMENT ON COLUMN public.sys_permission.permission_code IS '权限编码，必填且全表唯一，区分大小写，例如 user:list、user:create';
COMMENT ON COLUMN public.sys_permission.permission_name IS '权限中文名称，必填';
COMMENT ON COLUMN public.sys_permission.permission_type IS '权限类型，必填：0 目录、1 菜单、2 历史独立按钮、3 接口及操作按钮共用权限';
COMMENT ON COLUMN public.sys_permission.route_path IS '前端路由路径，可为空，非空时以 / 开头；不用于后端接口鉴权';
COMMENT ON COLUMN public.sys_permission.api_path IS '后端接口匹配路径，仅接口权限必填，以 / 开头，例如 /api/users';
COMMENT ON COLUMN public.sys_permission.http_method IS '接口请求方法，仅接口权限必填，使用显式大写方法，不支持 *';
COMMENT ON COLUMN public.sys_permission.icon IS '前端展示图标标识，可为空';
COMMENT ON COLUMN public.sys_permission.status IS '状态：0 正常、1 禁用，默认 0；禁用权限不参与鉴权';
COMMENT ON COLUMN public.sys_permission.sort_order IS '同级展示顺序，非负数，数值越小越靠前，默认 0';
COMMENT ON COLUMN public.sys_permission.description IS '权限用途说明，可为空';
COMMENT ON COLUMN public.sys_permission.created_at IS '创建时间，数据库默认当前事务时间';
COMMENT ON COLUMN public.sys_permission.created_by IS '创建人 ID，必填，系统初始化填 0';
COMMENT ON COLUMN public.sys_permission.updated_at IS '最近更新时间，插入时默认当前时间，后续由业务代码更新';
COMMENT ON COLUMN public.sys_permission.updated_by IS '最近修改人 ID，可为空，系统修改填 0，由业务维护';

-- 权限编码已建立唯一索引；这里补充按父节点和排序值查询权限树所需索引。
CREATE INDEX idx_sys_permission_parent_sort ON public.sys_permission (parent_id, sort_order, id);
-- 同一路径的不同 HTTP 方法可以分别授权；同一路径和方法不得定义多个冲突权限。
-- 此索引仅保证路径文本不重复，路径模式之间是否重叠仍由业务校验。
CREATE UNIQUE INDEX uk_sys_permission_api ON public.sys_permission (api_path, http_method)
    WHERE permission_type = 3;

-- 四、用户角色：一个用户可分配多个角色，同一用户与角色组合只保留一条有效关系。
-- 撤销角色通过删除关系完成，不增加逻辑删除字段，避免重复授权和唯一键冲突。
CREATE TABLE public.sys_user_role (
    id              BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    role_id         BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT          NOT NULL,

    CONSTRAINT pk_sys_user_role PRIMARY KEY (id)
);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_user_role ON public.sys_user_role (user_id, role_id);

COMMENT ON TABLE public.sys_user_role IS '用户角色关系表；用户与角色多对多，删除关系即撤销角色';
COMMENT ON COLUMN public.sys_user_role.id IS '关系主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_user_role.user_id IS '用户 ID，必填，逻辑关联 sys_user，关联校验及删除清理由代码维护';
COMMENT ON COLUMN public.sys_user_role.role_id IS '角色 ID，必填，逻辑关联 sys_role，关联校验及删除清理由代码维护';
COMMENT ON COLUMN public.sys_user_role.created_at IS '角色分配时间，默认当前事务时间';
COMMENT ON COLUMN public.sys_user_role.created_by IS '角色分配操作者 ID，必填，系统分配填 0';

-- 组合唯一键覆盖按用户查角色；反向索引用于按角色查用户及由代码执行的关系清理。
CREATE INDEX idx_sys_user_role_role_user ON public.sys_user_role (role_id, user_id);

-- 五、角色权限：一个角色可以拥有多个权限，一个权限也可以授予多个角色。
CREATE TABLE public.sys_role_permission (
    id              BIGINT          NOT NULL,
    role_id         BIGINT          NOT NULL,
    permission_id   BIGINT          NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT          NOT NULL,

    CONSTRAINT pk_sys_role_permission PRIMARY KEY (id)
);

-- 唯一索引保证并发写入时仍保持唯一性，不包含业务状态或关联关系判断。
CREATE UNIQUE INDEX uk_sys_role_permission ON public.sys_role_permission (role_id, permission_id);

COMMENT ON TABLE public.sys_role_permission IS '角色权限关系表；删除关系即撤销角色的对应权限';
COMMENT ON COLUMN public.sys_role_permission.id IS '关系主键，应用生成的正数雪花 ID';
COMMENT ON COLUMN public.sys_role_permission.role_id IS '角色 ID，必填，角色删除后的关系清理由代码维护';
COMMENT ON COLUMN public.sys_role_permission.permission_id IS '权限 ID，必填，权限删除后的关系清理由代码维护';
COMMENT ON COLUMN public.sys_role_permission.created_at IS '权限授予时间，默认当前事务时间';
COMMENT ON COLUMN public.sys_role_permission.created_by IS '权限授予操作者 ID，必填，系统授权填 0';

-- 组合唯一键覆盖按角色查权限；反向索引用于按权限查角色及由代码执行的关系清理。
CREATE INDEX idx_sys_role_permission_permission_role
    ON public.sys_role_permission (permission_id, role_id);

COMMIT;
