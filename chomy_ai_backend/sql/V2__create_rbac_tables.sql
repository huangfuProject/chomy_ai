-- RBAC 权限模型：用户 -> 用户角色 -> 角色 -> 角色权限 -> 权限。
-- 执行前提：连接 chomy_ai，并已执行 V1__create_user_table.sql 创建 public.sys_user。
-- 使用具有 public 模式建表权限的账号执行一次；本项目不自动执行迁移。
-- 所有主键由应用按 MyBatis-Plus ASSIGN_ID 生成；手工写入时须显式提供正数 ID。
-- 时间字段使用 TIMESTAMPTZ；created_by、updated_by 为审计字段，0 表示系统操作。
-- 不建立外键、CHECK、函数或触发器；关联关系、业务校验及更新时间由代码维护。
-- 本脚本只建立结构，不创建默认管理员、角色、授权关系或实际业务接口。
-- @author chomy
BEGIN;

-- 一、角色：角色编码唯一，用于稳定的权限判断；展示名称可以重复。
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

-- 二、权限：目录、菜单、按钮、接口使用统一权限码，树形关系只负责组织展示。
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

-- 三、用户角色：一个用户可分配多个角色，同一用户与角色组合只保留一条有效关系。
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

-- 四、角色权限：一个角色可以拥有多个权限，一个权限也可以授予多个角色。
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

-- 五、鉴权查询示例（仅注释，不会自动执行）。
-- :user_id 表示绑定参数；用户必须正常且激活，角色及权限必须正常。
-- 多角色权限取并集，使用 DISTINCT 去重；无角色、无权限、被冻结或未激活均不会获得权限。
-- SELECT DISTINCT p.permission_code
-- FROM public.sys_user u
-- JOIN public.sys_user_role ur ON ur.user_id = u.id
-- JOIN public.sys_role r ON r.id = ur.role_id AND r.status = 0
-- JOIN public.sys_role_permission rp ON rp.role_id = r.id
-- JOIN public.sys_permission p ON p.id = rp.permission_id AND p.status = 0
-- WHERE u.id = :user_id AND u.status = 0 AND u.actived = 1;
-- 菜单展示可额外加载祖先节点用于构建树，但祖先节点展示不能等同于授予接口权限。
-- 父节点被禁用不会自动修改子节点状态；若业务需要禁用整棵子树，须显式批量禁用子节点。
-- 角色、权限、用户状态及关联关系改变后，后续鉴权实现应及时失效相关权限缓存。
