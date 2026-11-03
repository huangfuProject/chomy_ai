# Chomy AI Backend

Java 21 + Maven + Spring Boot 3.5.16 + MyBatis-Plus 3.5.18 + Spring Data Redis + PostgreSQL + Nacos 配置中心。

## 配置文件

应用配置全部采用 Properties：

- `src/main/resources/application.properties`：公共配置、Nacos 导入和数据库、Redis 参数映射，默认激活 `dev`。
- `src/main/resources/application-dev.properties`：开发环境，Nacos 默认地址 `127.0.0.1:8848`。
- `src/main/resources/application-prod.properties`：生产环境，通过 `NACOS_SERVER_ADDRESS`、`NACOS_USERNAME`、`NACOS_PASSWORD` 占位符获取 Nacos 地址与认证信息，不设置默认值。

开发环境直接连接 `127.0.0.1:8848`，使用已配置的 `nacos` 账号及密码获取配置，无需额外配置文件或导出认证环境变量。生产环境须提供上述三个环境变量。Nacos 默认命名空间 ID 为 `34a480c0-e54d-47f5-9524-726dfe7d82b8`，Data ID 为 **`chomy_ai_be`**（不添加 `.properties` 后缀），Group 为 `DEFAULT_GROUP`，配置格式为 **Properties**。数据库参数读取 `db.host`、`db.port`、`db.name`、`db.username`、`db.password`，Redis 参数读取 `redis.host`、`redis.port`、`redis.password`。

通过 `spring.config.import` 加载 Nacos 配置，接入方式参考 [Nacos 官方 Spring Boot 3 文档](https://www.nacos.io/en/docs/next/ecology/use-nacos-with-spring-boot3/)。本项目使用独立的 `spring-alibaba-nacos-config`，配置前缀为 `spring.nacos.config`。Nacos 不可用或配置缺失时不会回退到本地数据库参数。修改连接参数后需重启应用，以重建数据库与 Redis 连接。

## 运行

需要 JDK 21、Maven 3.6.3+，并确保所配置的 Nacos 可访问且目标配置已存在。

```bash
cd /Users/huangfukexing/developTool/codex_project/chomy_ai/chomy_ai_backend
mvn spring-boot:run
```

默认以 `dev` 启动。生产环境示例：

```bash
export NACOS_SERVER_ADDRESS=your-nacos-host:8848
export NACOS_USERNAME=your-nacos-user
export NACOS_PASSWORD='替换为生产 Nacos 密码'
export JWT_SECRET_BASE64='替换为固定的至少32字节随机密钥的Base64编码'
java -jar target/chomy-ai-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

两种环境均支持 `NACOS_NAMESPACE_ID`、`NACOS_CONFIG_DATA_ID`、`NACOS_CONFIG_GROUP` 覆盖配置标识；默认读取用户指定的同一个配置项。环境隔离时可指定不同命名空间或 Group。

应用配置直接设置 `spring.profiles.active=dev`、`server.port=8001`，无需额外环境变量。其他环境变量：`REDIS_DATABASE`（默认 `0`）、`DB_SOCKET_TIMEOUT`（默认 `60` 秒）。Maven 不会自动读取 `.env`，请在终端导出环境变量或在 IDE 运行配置中设置。

PostgreSQL JDBC URL 配置了应用连接标识 `ApplicationName`、10 秒建连超时、60 秒 socket 读取超时、TCP 保活，以及批量插入重写。读取超时后驱动会关闭连接；长查询场景可通过 `DB_SOCKET_TIMEOUT` 调整，`0` 表示不限制。参数含义见 [pgJDBC 官方文档](https://jdbc.postgresql.org/documentation/use/)。Hikari 的 `connection-timeout=10000` 单位为毫秒，控制等待连接池连接的时间。

每个新建的数据库物理连接都会执行 `SET TIME ZONE 'Asia/Shanghai'`，确保数据库会话时区为北京时间。该设置只影响会话，不修改数据库全局配置或 JVM 时区。

```bash
curl http://localhost:8001/health/health
curl http://localhost:8001/actuator/health
```

`/health/health` 检查应用 HTTP 服务；`/actuator/health` 检查应用依赖状态，依赖不可用时返回 DOWN / HTTP 503。

## Web 基础设施

业务控制器的普通对象、集合和字符串会自动封装为以下 JSON；已返回 `ApiResult` 的结果不会重复包装：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": "ok",
  "durationMs": 3
}
```

`durationMs` 单位为毫秒，使用单调时钟统计从请求进入过滤器到响应开始序列化的处理时间，成功和异常响应都会填充；不包含序列化、网络传输和客户端接收时间。文件下载、二进制、SSE、204/205/304 无内容响应及 Actuator 保留原协议。

`BizException` 是可继承的业务异常基类，`ErrorCode` 是错误枚举公共接口（Java 枚举不能继承其他枚举），`CommonErrorCode` 提供通用业务错误。业务模块可以实现 `ErrorCode` 定义专属枚举，也可以继承 `BizException` 定义模块异常。现有 `BusinessException` 继承 `BizException`，保持原调用兼容。

```java
// 使用公共枚举的业务码及默认中文提示。
throw new BizException(CommonErrorCode.DATA_NOT_FOUND);

// 保留枚举错误码，用具体的中文业务描述替换默认提示。
throw new BizException(CommonErrorCode.INVALID_PARAMETER, "开始日期不能晚于结束日期");

// 可指定自定义业务码，或使用 BizException("中文提示") 默认业务码 400。
throw new BizException(20001, "当前任务已完成，不能重复提交");
```

所有 `BizException` 及其子类均返回 HTTP 400，自定义业务码写入 `code`，不替代 HTTP 状态；可通过 `BizException(ErrorCode, Throwable)` 保留底层原因，前端仅收到枚举的公开提示。参数校验、JSON 格式错误、404、405、415 等通过全局异常处理器返回统一结构，并保留实际 HTTP 状态及必要响应头。未预期异常返回 HTTP 500，前端只收到通用中文提示，完整异常写入服务端日志。业务异常及校验消息应使用可公开的中文描述。

全局 CORS 过滤器放行任意来源、方法和请求头，支持 `Authorization` 自定义请求头；预检缓存 3600 秒，不启用跨域 Cookie 凭据。普通及异常响应均附带跨域响应头。参见 [Spring CORS 文档](https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html)。

`WebInfrastructureTest` 使用独立 MockMvc 验证序列化、错误状态、请求耗时、二进制响应与跨域预检，不依赖 Nacos、PostgreSQL 或 Redis。

## 数据库初始化

首次使用时，连接 PostgreSQL 的 `postgres` 数据库，执行 `sql/V0__create_database.sql`：

```bash
psql -h 81.70.166.182 -p 5432 -U root -d postgres -W -f sql/V0__create_database.sql
```

脚本创建 `chomy_ai`，默认所有者为执行建库的账号；执行用户需要 `CREATEDB` 权限。脚本仅首次手动执行，不自动执行，也不应放在事务中执行。现有数据库不会因脚本修改而变更所有者。

`compose.yml` 可用于启动本地 PostgreSQL、Redis；若使用本地依赖，需要在对应 Nacos 配置中调整 `db.*`、`redis.*`，与 Compose 的账号及密码保持一致。

## 用户表

建表脚本位于服务根目录的 `sql/V1__create_rbac_tables.sql`。先创建数据库，再连接 `chomy_ai` 手动执行该脚本；项目尚未接入自动迁移，不会在应用启动时自动建表：

```bash
# 使用具有建表权限的数据库账号执行；遇到 SQL 错误立即停止。
psql -h 81.70.166.182 -p 5432 -U root -d chomy_ai -W -v ON_ERROR_STOP=1 -f sql/V1__create_rbac_tables.sql
```

表名为 `public.sys_user`，字段如下：

| 字段 | 说明 | 约束及默认值 |
| --- | --- | --- |
| `id` | 用户主键 | 必填、主键，应用生成正数雪花 ID |
| `username` | 用户名 | 必填、唯一、区分大小写，最长 64 个字符 |
| `nickname` | 昵称 | 必填、不允许空串或首尾空格、允许重名，最长 64 个字符 |
| `gender` | 性别 | 必填，0 未知、1 男、2 女，默认 0 |
| `phone` | 手机号 | 可空、提供时唯一，最长 32 个字符 |
| `email` | 邮箱 | 可空、不要求唯一，最长 254 个字符 |
| `password_hash` | 密码密文 | 必填，最长 255 个字符，保存带盐的不可逆哈希 |
| `signature` | 个性签名 | 可空，最长 255 个字符 |
| `avatar_url` | 头像 URL | 可空，最长 2048 个字符 |
| `created_at` | 创建时间 | 必填，数据库默认当前时间 |
| `create_type` | 创建方式 | 必填，0 管理员追加、1 自注册 |
| `created_by` | 创建人 ID | 必填，管理员追加填操作者 ID，自注册填自身 ID，系统初始化可填 0 |
| `status` | 状态 | 必填，0 正常、1 冻结，默认 0 |
| `actived` | 激活标记 | 必填，0 未激活、1 激活，默认 1 |
| `updated_at` | 更新时间 | 必填，插入时默认当前时间，后续由业务代码更新 |
| `last_login_at` | 最近成功登录时间 | 可空，由后续登录业务在认证成功后维护 |

创建时间、更新时间及登录时间使用 `TIMESTAMPTZ`，实体对应 `OffsetDateTime`。用户名、昵称、手机号和密码哈希的非空白、格式及状态取值校验由业务代码负责，数据库仅保留 NOT NULL、默认值及必要索引。用户名、手机号的唯一性由唯一索引保障。手机号标准化、创建人合法性及关联关系均由后续业务处理，不建立外键或 CHECK 约束。

`entity.User` 和 `mapper.UserMapper` 提供 MyBatis-Plus 实体及基础数据访问。密码哈希默认不参与查询、JSON 输出或 `toString`；后续登录查询需显式选择 `password_hash`。时间字段插入时可使用数据库默认值，后续更新时间须由业务代码显式赋值，不使用触发器。用户端登录和个人资料接口见下文；不会自动插入初始账号或开放注册。

## RBAC 表结构

RBAC 四张表与用户表统一由 `sql/V1__create_rbac_tables.sql` 创建，无需单独执行其他建表脚本。

| 表名 | 用途 | 核心约束 |
| --- | --- | --- |
| `sys_role` | 角色定义 | 角色编码唯一；状态 0 正常、1 禁用 |
| `sys_permission` | 目录、菜单、按钮及接口权限 | 权限编码唯一；接口路径和方法组合唯一；父子关系由代码维护 |
| `sys_user_role` | 用户与角色多对多关系 | 用户与角色组合唯一；引用现有 `sys_user` |
| `sys_role_permission` | 角色与权限多对多关系 | 角色与权限组合唯一 |

关系为 `sys_user → sys_user_role → sys_role → sys_role_permission → sys_permission`。主键由应用生成正数雪花 ID；创建人 ID 必填，系统操作可填 0。角色和权限包含创建、修改审计字段，更新时间由业务代码维护。关联表保留分配时间及操作者，不使用逻辑删除，撤销授权时删除关系。

权限类型：0 目录、1 菜单、2 历史独立按钮、3 接口及其操作按钮共用权限。顶层父节点为 NULL，父节点是否存在、自引用、多层循环、删除清理及父子类型规则均由业务代码维护，不配置数据库外键和级联操作。接口权限在业务上应同时提供以 `/` 开头的路径和显式大写 HTTP 方法，其他权限不填写这两个字段；这些规则由代码校验，不建立 CHECK 约束。前端 `route_path` 与后端 `api_path` 分别存储，路径模式重叠检查由后续业务实现。

多个正常角色的正常权限取并集；鉴权时还须检查用户 `status=0`、`actived=1`。目录、菜单及历史独立按钮不隐式授予接口权限；新的接口和操作按钮使用同一个类型 3 节点与权限码，父子结构仍不隐式继承授权。禁用父权限不会自动禁用子节点。删除用户、角色或权限时，由业务代码在事务内清理相应授权关系；数据库不执行级联删除。后续权限缓存应在分配、撤销或状态变更后及时失效。

脚本仅创建表、注释和索引，保留主键、NOT NULL 与字段默认值，不创建自定义函数、触发器、外键或 CHECK。V1 不插入数据，V2 负责默认角色、管理员及权限初始化。已实现 JWT、Redis 登录鉴权；后台角色、权限及授权关系的管理接口仍需后续实现，用户管理接口已提供。

## Entity、Mapper 与 Service

五张表均提供 MyBatis-Plus 基础代码：

| 数据表 | Entity | Mapper | Service / 实现类 |
| --- | --- | --- | --- |
| `sys_user` | `User` | `UserMapper` | `UserService` / `UserServiceImpl` |
| `sys_role` | `Role` | `RoleMapper` | `RoleService` / `RoleServiceImpl` |
| `sys_permission` | `Permission` | `PermissionMapper` | `PermissionService` / `PermissionServiceImpl` |
| `sys_user_role` | `UserRole` | `UserRoleMapper` | `UserRoleService` / `UserRoleServiceImpl` |
| `sys_role_permission` | `RolePermission` | `RolePermissionMapper` | `RolePermissionService` / `RolePermissionServiceImpl` |

实体位于 `entity`，Mapper 位于 `mapper`，服务接口位于 `service`，实现类位于 `service.impl`。Mapper 继承 `BaseMapper<T>`，Service 继承 `IService<T>`，实现类继承 `ServiceImpl<Mapper, Entity>` 并注册为 Spring 服务；通用 CRUD 不重复编写 XML。当前版本 MyBatis-Plus 3.5.18 的 Service 类型位于 `com.baomidou.mybatisplus.spring.service`。

实体使用 Lombok Getter/Setter，雪花 ID 使用 `ASSIGN_ID`，Long 类型标识输出为 JSON 字符串，时间类型使用 `OffsetDateTime`。创建时间使用数据库默认值并禁止通用更新覆盖，更新时间由业务显式赋值。新增 RBAC 实体的可默认字段不设置 Java 初始值，插入时可使用数据库默认值，也避免部分字段更新时意外重置状态或排序。

可直接注入 `UserService`、`RoleService` 等调用通用查询、分页、保存和批量操作。条件查询优先使用 Lambda 包装器或链式 API：

```java
// 查询正常角色，按显示顺序排序；roleService 由 Spring 注入。
roleService.lambdaQuery()
        .eq(Role::getStatus, EnableStatus.ENABLED)
        .orderByAsc(Role::getSortOrder)
        .list();
```

RBAC Service 保持持久化基础骨架，用户 Service 已实现资料白名单更新、密码校验及哈希生成。授权关系有效性、级联清理和审计操作者仍需具体业务实现。后续多表业务需在 Service 中显式维护规则并开启事务。可空字段清空时应通过明确的更新包装器设置 NULL，默认部分更新会跳过 NULL 属性。

`PersistenceInfrastructureTest` 不连接数据库，使用真实 MyBatis-Plus 验证五组 Mapper、Service 装配及基础 SQL 注入；数据库表仍需按版本手动初始化。

## 固定业务值枚举

固定值集中定义在 `cn.chomy.ai.backend.enums`，实体、DTO、业务判断和 MyBatis-Plus 条件均使用枚举：

| 枚举 | 用途 | 数字编码 |
| --- | --- | --- |
| `Gender` | 性别 | UNKNOWN=0、MALE=1、FEMALE=2 |
| `UserCreateType` | 用户创建方式 | ADMIN_CREATED=0、SELF_REGISTERED=1 |
| `UserStatus` | 用户状态 | NORMAL=0、FROZEN=1 |
| `ActivationStatus` | 激活状态 | INACTIVE=0、ACTIVE=1 |
| `EnableStatus` | 角色及权限状态 | ENABLED=0、DISABLED=1 |
| `PermissionType` | 权限资源类型 | DIRECTORY=0、MENU=1、BUTTON=2（历史兼容）、API=3（接口与操作按钮共用） |

每个枚举实现 `CodeEnum`，包含稳定 `code` 和中文说明。MyBatis-Plus 使用 `@EnumValue` 按 code 读写数据库，不保存枚举名称或 ordinal；Jackson 使用 `@JsonValue` 输出数字，HTTP 和 Redis JSON 保持原有格式，例如 `gender: 2`。已有数字缓存无需迁移，数据库仍使用 SMALLINT，不新增数据库枚举类型、CHECK 或函数。接入方式参考 [MyBatis-Plus 官方枚举映射文档](https://baomidou.com/en/guides/auto-convert-enum/)。

请求只接受已定义的整数编码：未知值、小数、数字字符串、枚举名称和布尔值均拒绝，不自动截断或转换成默认状态。必填字段继续通过 `@NotNull` 校验，未知数据库状态或可空枚举不得自动回退为正常、启用或已激活。

`BusinessEnumMappingTest` 验证数字 JSON/Redis 兼容性、非法输入拒绝及实际 JDBC 类型处理器的数字读写；安全集成测试验证接口 JSON 编码和认证状态判断保持一致。

## Spring Security、JWT 与用户接口

新增用户端接口位于 `cn.chomy.ai.backend.controller.user`：

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/user/login` | 用户名密码登录，返回 JWT、用户资料、角色、权限 | 公开 |
| POST | `/user/logout` | 删除当前 JWT 对应的 Redis 会话 | Bearer JWT |
| GET | `/user/info` | 查看当前用户资料，不含密码哈希 | Bearer JWT |
| PUT | `/user/info` | 修改昵称、性别、个性签名、头像 | Bearer JWT |
| PUT | `/user/password` | 验证原密码并修改密码 | Bearer JWT |

用户控制器只负责路由、参数校验、获取认证主体和委托 Service。`LoginController` 调用 `LoginService`，认证、登录时间维护、JWT/Redis 会话编排由 `LoginServiceImpl` 完成；`UserInfoController` 直接返回 `UserService` 的业务结果。Controller 不直接依赖 Mapper、Redis 或 AuthenticationManager。

这些个人接口只从 JWT 的 `sub` 获取用户 ID，不接受外部指定目标用户，不依赖角色即可维护自身资料。`/health/health` 和 Actuator health 的 GET 保持公开。其他业务接口必须配置类型为 3 的 `sys_permission`，按 `http_method` 和 Spring PathPattern 格式的 `api_path` 匹配；未配置时拒绝访问。同等具体模式须全部授权，精确路径优先于宽泛路径。无超级管理员隐式绕过。

新数据库依次执行 V0 建库、V1 建表、V2 插入初始化数据。V1 已包含性别、可空手机号及邮箱的最终字段定义；这些脚本用于空库初始化，已有数据库应单独核对结构差异，不能重复执行 V1。本次脚本整理不会自动迁移真实数据库。

登录请求示例（密码为示意，须替换为已有账号的真实密码）：

```json
{"username":"已有用户名","password":"xxxxxx"}
```

数据库 `password_hash` 须保存 BCrypt 哈希，支持标准 `$2a$` / `$2b$` 格式及 `{bcrypt}` 前缀；服务端新密码编码使用带 `{bcrypt}` 前缀的 BCrypt，不支持明文及 `{noop}`。首次账号及角色分配需由初始化或后续后台流程完成，项目不创建默认账号。

登录响应继续使用 `code / message / data / durationMs`，`data` 包含 `accessToken`、`tokenType=Bearer`、`expiresIn`（秒）、`user`、`roles`、`permissions`。登录时只加载正常角色、正常权限，权限去重后与角色分别缓存在 Redis 的 `chomy:auth:roles:<jti>`、`chomy:auth:permissions:<jti>` 中，会话元信息保存在 `chomy:auth:session:<jti>`，三者默认有效期均为 7200 秒，缺失任一项均拒绝认证。JWT 仅含用户 ID、jti、签发者和时间，不把完整角色权限放入 JWT。Redis 中不保存 JWT 原文、原密码或密码哈希，保存密码哈希的 SHA-256 指纹用于版本核对。后续请求使用：

```http
Authorization: Bearer <accessToken>
```

MVC 层新增 `security.LoginInterceptor`，由 `config.WebMvcConfig` 注册到所有路径，在控制器执行前检查 Spring Security 上下文中是否存在已认证的 `JwtAuthenticationToken`。登录及健康检查与安全链共用 `SecurityConfig.publicEndpointMatcher` 白名单，按 HTTP 方法和路径精确放行；真实 CORS 预检也可通过。未登录由现有 `SecurityResponseHandler` 输出包含 `durationMs` 的统一 401，不跳转页面。仅携带 Token 请求头或用户 ID 不能通过状态检查。JWT 验签、Redis 会话及用户状态仍由前置安全链执行，拦截器不重复访问数据库或 Redis，也不替代 RBAC 校验。

JWT 签名、签发者或有效期不正确、Redis 会话缺失、用户被冻结/未激活、密码版本变化，均返回统一 401。角色权限来自登录时快照，后台调整角色、权限或授权关系后必须对受影响用户调用 `JwtSessionService.revokeUserSessions(userId)`，使旧会话失效并要求重新登录；同一秒重新签发的会话也可能保守失效，下一秒重试即可。接口资源定义和禁用状态实时查询数据库。权限不足返回 403，认证 Redis/数据库故障返回 503，不回退到仅检查 JWT 签名。

`SecurityConfig.securityFilterChain` 内部使用详细中文行注释解释 CSRF、CORS、无状态会话、公开及个人接口、RBAC 兜底规则、JWT 认证转换和统一异常处理的配置目的。

方法级权限通过 `@PreAuthorize("hasAuthority('user:list')")` 或 `@PreAuthorize("hasRole('admin')")` 补充，不能替代请求级接口配置。角色权限区分：角色编码转为 `ROLE_<role_code>`，权限码直接作为 authority，`ROLE_` 为保留前缀，权限码不得使用该前缀。该实现参考 [Spring Security JWT 文档](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html) 和 [方法鉴权文档](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/method-security.html)。

个人资料修改为完整表单，`nickname`、`gender` 必填；`signature`、`avatarUrl` 可为 null 或空字符串以清空。接口只更新白名单字段，不能修改用户名、手机号、角色、冻结状态或激活状态。头像只接受 HTTP/HTTPS URL：

```json
{"nickname":"新昵称","gender":1,"signature":"个性签名","avatarUrl":"https://example.com/avatar.png"}
```

修改密码需提供 `oldPassword`、`newPassword`。新密码至少 8 个字符、UTF-8 最多 72 字节，不得与旧密码相同。数据库更新使用旧哈希条件进行并发检查，成功后由于密码指纹改变，当前及所有设备的旧 Token 都失效，需重新登录；普通退出只撤销当前 jti。

配置 `security.jwt.ttl-seconds=7200` 控制 JWT 和 Redis 会话有效期。`security.jwt.secret-base64=${JWT_SECRET_BASE64:}` 为 HS256 签名密钥，Base64 解码后至少 32 字节；生产必须由部署环境设置固定随机密钥，多实例保持一致。开发环境留空时生成不落盘的临时随机密钥，重启后旧 JWT 失效。使用无状态 Bearer 请求头，不启用 Cookie/Session、表单登录或 HTTP Basic；基于此关闭 CSRF，保留现有 CORS 放行。

`SecurityIntegrationTest` 使用真实安全链、BCrypt 和 JWT，模拟数据库与 Redis 边界，覆盖登录缓存、令牌失效、密码修改、个人资料白名单、接口权限、方法权限、CORS 及认证基础设施故障。

## 后台用户管理

控制器为 `cn.chomy.ai.backend.controller.admin.UserController`，路径前缀 `/admin/users`，仅接收请求并委托 `AdminUserService`。权限在请求级 RBAC 和 Service `@PreAuthorize` 两处校验，权限码集中定义在 `UserManagementPermission`：

| 方法 | 路径 | 功能 | 接口权限码 |
| --- | --- | --- | --- |
| POST | `/admin/users` | 创建用户 | `user:update:create` |
| PUT | `/admin/users/{id}/activate` | 激活用户，不同时解冻 | `user:update:activate` |
| PUT | `/admin/users/{id}/freeze` | 冻结用户 | `user:update:freeze` |
| PUT | `/admin/users/{id}/unfreeze` | 解冻用户 | `user:update:unfreeze` |
| PUT | `/admin/users/{id}/password` | 管理员重置密码，无需原密码 | `user:update:password` |
| PUT | `/admin/users/{id}` | 修改用户资料 | `user:update:info` |
| DELETE | `/admin/users/{id}` | 删除用户及用户角色关联 | `user:update:delete` |
| GET | `/admin/users/{id}` | 查看用户详情 | `user:get:detail` |
| GET | `/admin/users` | 分页查询所有用户 | `user:get:page` |

创建、所有修改操作及删除使用 `user:update:*` 权限组，读取使用 `user:get:*`；组名不代表隐式通配授权，每个接口须单独分配对应权限。SQL 位于 `sql/V2__init_rbac_and_user_data.sql`，在 V1 建表后手动执行，一次初始化 2 个顶层目录、1 个菜单和 9 个接口/操作按钮共用节点，共 12 个权限资源：

```bash
# 写入权限种子及管理员、角色和显式授权关系；不会自动执行。
psql -h 81.70.166.182 -p 5432 -U root -d chomy_ai -W -v ON_ERROR_STOP=1 -f sql/V2__init_rbac_and_user_data.sql
```

菜单编码为 `user:manage`，前端路由为 `/user-management`，归属“用户后台”目录。所有操作节点直接挂在菜单下，保持 `permission_type=3` 和原有接口码，例如 `user:update:create` 同时用于前端创建按钮显隐及后端 POST `/admin/users` 鉴权。前端只检查登录返回权限清单中的同一个码，不再单独分配 `user:button:create`。仅有菜单不能执行操作，不同接口之间也不隐式互相授权。

V2 同时初始化以下菜单结构。大类使用现有目录类型 `0`，页面使用菜单类型 `1`，无需修改表结构：

| 菜单大类 | 大类权限码 | 子菜单 | 菜单权限码 | 页面路由 |
| --- | --- | --- | --- | --- |
| 用户前台 | `menu:frontend` | 暂无子菜单 | — | — |
| 用户后台 | `menu:backend` | 用户管理 | `user:manage` | `/user-management` |

V2 已包含最终菜单结构，在 V1 后执行即可。用户前台保留目录，暂不设置子菜单。目录和菜单不隐式授予子权限；目录、菜单及接口均须显式授权给角色，前端可以补齐子菜单的祖先目录用于展示，但不能据此扩大授权。

权限种子按编码幂等插入，不覆盖已有状态或自定义配置；固定主键或接口唯一索引冲突时回滚。V2 创建管理员和超级管理员角色，并在全部权限种子插入后，将全部权限显式关联到 `super_admin`；普通角色仍须单独授权。执行后须撤销受影响用户旧会话并重新登录，刷新 Redis 中的权限快照。当前接口码与前端按钮检查示例：

```javascript
// 接口和按钮使用同一个权限码，不再维护独立按钮授权清单。
const permissionCodes = new Set(permissions.map(item => item.permissionCode));
const canCreateUser = permissionCodes.has('user:update:create');
```

创建表单包含 `username`、`nickname`、`phone`、`password`、可选 `gender`、`actived`、`signature`、`avatarUrl`。用户名唯一，仅允许英文字母、数字和下划线，其余字符返回“非合法用户名”，不自动修剪用户名空格。手机号非必填，提供时唯一，采用可选 `+` 国家码和 6 至 20 位数字；新增非必填 `email`，提供时校验邮箱格式。手机号和邮箱的空字符串保存为 NULL。创建方式固定为管理员追加，创建人从认证 JWT 取得；状态默认正常，性别默认未知，激活默认 1，允许创建时显式指定未激活。不通过创建表单分配角色，密码只保存 BCrypt 哈希。

修改资料为完整 PUT 表单：`username`、`nickname`、`gender` 必填，`phone`、`email` 非必填，省略或空字符串表示清空，签名及头像可为空以清除。请求中即使出现密码、角色、状态、激活、创建人或创建方式，也不会成为更新字段。用户名或手机号变更后要求目标用户重新登录。状态和密码使用各自独立接口，不经通用资料修改绕过独立操作权限。

分页缺省 `current=1`、`size=20`，每页最多 100 条；支持 `username`、`nickname`、`phone` 字面量模糊查询，以及 `gender`、`status`、`actived` 数字枚举筛选，创建时间范围为 `createdFrom`、`createdTo`（ISO-8601 带时区格式，包含边界）。按创建时间倒序、ID 倒序稳定排序。示例：

```http
GET /admin/users?current=1&size=20&username=demo&status=1&actived=1&gender=2
Authorization: Bearer <accessToken>
```

结果仍由统一响应包装，`data.records` 包含用户名、昵称、手机号、邮箱、头像、性别、`status`、`actived`、`frozen`/`active` 布尔展示字段及创建时间，同时含 ID、签名和审计时间，不含密码；`data` 还包括 current、size、total 等 MyBatis-Plus 分页元数据。URL 枚举参数只接受已定义数字编码，不接受枚举名称或小数。

管理端写入显式维护更新时间，并对同一目标加行锁。冻结、解冻、密码重置及删除前撤销旧会话；Redis 故障中止敏感写入，事务失败时已撤销的会话保持失效，用户重新登录即可。冻结前的 Token 不会因解冻自动复活。禁止管理用户冻结或删除自身，重置自身密码仍允许且需要重新登录。删除为物理删除，用户及其 `sys_user_role` 关系在同一数据库事务内清理，不删除共享角色、权限或历史创建人审计引用。

`AdminUserServiceTest` 验证业务写入、并发唯一键提示、密码哈希、状态切换、会话撤销及关系清理；安全集成测试直接读取 V2 中的接口定义，验证路由匹配、读写权限隔离、接口与按钮共用同一权限码、菜单不隐式授予操作及服务层鉴权。

## 代码约定及构建

- 启动类：`cn.chomy.ai.backend.ChomyAiBackendApplication`。
- HTTP 控制器放在 `cn.chomy.ai.backend.controller`，用户端登录与个人资料接口位于 `controller.user.LoginController`、`controller.user.UserInfoController`。健康检查访问路径仍为 `/health/health`。用户表实体及 Mapper 保留在 `entity`、`mapper`。
- Java 日志使用 SLF4J，通过 Lombok `@Slf4j` 自动生成 `log` 字段；Maven 已配置注解处理器，Lombok 不打入运行包。日志使用 `{}` 占位符并保留必要的异常堆栈，不输出密码及令牌。Lombok 接入方式参考 [官方 Maven 配置说明](https://projectlombok.org/setup/maven)。
- MyBatis-Plus 已配置 PostgreSQL 分页插件，单页最多 500 条；Mapper 放在启动类子包并添加 `@Mapper`，继承 `BaseMapper<T>`。XML 放在 `src/main/resources/mapper/`。
- Redis 可直接注入 `StringRedisTemplate`；对象缓存需显式定义序列化方式。
- 用户和 RBAC 五张表统一由 V1 创建，V2 插入权限、管理员及显式授权数据；用户端接口已接入 JWT + Redis。

```bash
mvn clean verify
java -jar target/chomy-ai-backend-0.0.1-SNAPSHOT.jar
```

## 联系方式调整与管理员初始化

V1 直接定义可空手机号和 `email VARCHAR(254)`，不再保留独立的联系方式升级脚本；已有数据库须自行核对并补齐结构差异后再启动新版本。手机号唯一索引继续保留，多个未填手机号的用户可共存；邮箱不要求唯一。用户名创建、管理修改及登录表单均只允许 `[A-Za-z0-9_]+`，不允许中文、空格、连字符等其他字符。

个人资料查询和管理详情、分页均返回可空邮箱；个人资料修改允许维护邮箱，不允许借此修改用户名、手机号或角色。

V1 建表完成后，执行 `sql/V2__init_rbac_and_user_data.sql`，先初始化菜单权限，再初始化管理员：创建 `admin` 管理员，昵称“超级管理员”、性别男，正常且已激活；密码以 BCrypt 哈希保存。创建 `super_admin` 角色并显式关联全部权限，包括新项目及对话权限。脚本重复执行不重置已有账户密码或状态，后续新增权限须在对应版本 SQL 中同步授予超级管理员角色，不通过代码绕过鉴权。

普通测试用户只直接写入数据库，不纳入版本初始化 SQL。其角色只关联“用户前台”目录及其后代资源，不授予用户后台或用户管理接口权限。数据库账号需要用户、角色、权限和关联表的必要读写权限；结构迁移还需要表的所有者权限。

## 用户项目与对话

`sys_agent_project` 保存 `id`、`project_name`（最长 128 字符）、`description`（可空，最长 2000 字符）、`user_id` 和 `created_at`。`sys_agent_chat` 保存 `id`、可空 `project_id`、`chat_name`（最长 128 字符）、`type`、`user_id`、`archived` 和 `created_at`。项目名和对话名均允许重复，不建立名称唯一索引。主键使用应用生成的雪花 ID，响应中的 ID 为字符串；创建时间使用 `TIMESTAMPTZ`，由数据库默认生成。

`AgentChatType` 定义 0 无项目、1 有项目；`AgentChatArchiveStatus` 定义 0 未归档、1 已归档。类型为 0 时项目 ID 必须为空，类型为 1 时项目必须存在且属于同一用户。内部 `AgentChatService.createChat` 负责这些校验，并在项目对话写入前锁定项目行，与项目删除互斥；新对话显式设为未归档。聊天创建、归档、恢复及移动接口见下方说明。继承的通用 CRUD 仅供内部可信持久化调用，不能直接作为客户端写入入口；后续对话创建业务应调用上述专用方法。

新库继续使用 V0 → V1 → V2：V1 统一创建七张表，V2 插入原有初始化数据和新增九个接口权限。已有数据库尚无项目与对话表时，先手动执行 `sql/V3__create_agent_project_chat_table.sql` 一次，再执行 V4；V3 新增两张表、注释、索引和原六个权限，V4 补齐项目分页权限及超级管理员授权；不要在已有库重复执行全量 V1。项目不会自动执行 SQL。本次不创建外键、CHECK、函数或触发器，关系校验和删除清理在业务代码完成。

| 方法 | 路径 | 用途 | 独立权限码 |
| --- | --- | --- | --- |
| GET | `/user/agent/projects` | 本人全部项目分页 | `agent:project:page` |
| PUT | `/user/agent/projects/{projectId}` | 修改本人项目名称及介绍 | `agent:project:update` |
| PUT | `/user/agent/chats/{chatId}/name` | 重命名本人对话 | `agent:chat:rename` |
| POST | `/user/agent/projects` | 创建本人项目 | `agent:project:create` |
| GET | `/user/agent/projects/{projectId}/chats` | 本人指定项目对话分页 | `agent:chat:project` |
| GET | `/user/agent/chats/standalone` | 本人非项目对话分页 | `agent:chat:standalone` |
| GET | `/user/agent/chats/archived` | 本人全部归档对话分页 | `agent:chat:archived` |
| DELETE | `/user/agent/chats/{chatId}` | 删除本人对话 | `agent:chat:delete` |
| DELETE | `/user/agent/projects/{projectId}` | 删除本人项目及全部关联对话 | `agent:project:delete` |

创建表单为 `{"projectName":"项目名称","description":"可选介绍"}`，服务端去除项目名首尾空白，拒绝空白名称。用户身份只从已验签 JWT 取得，不接受表单指定的用户 ID。全部查询和单条删除按认证用户隔离，他人项目/对话与不存在的记录均使用相同业务错误。删除项目在事务中先锁定本人的项目，再物理删除全部关联对话（包含归档对话），最后删除项目；单条对话删除不影响项目。

分页参数为 `current`（默认 1）和 `size`（默认 20，最大 100），结果沿用 `ApiResult` 包装及 MyBatis-Plus 分页结构，按 `created_at DESC, id DESC` 稳定排序。项目对话和非项目对话列表包含归档记录；归档列表仅包含 `archived=1`，同时覆盖有项目及无项目对话。对话记录均返回 `projectName`，没有项目时 `projectId`、`projectName` 为空；项目名称按当前页项目 ID 批量读取，避免逐条查询。

项目分页接口为 `GET /user/agent/projects?current=1&size=20`，仅返回已验签 JWT 所属用户的全部项目，不接受客户端指定用户。复用 `AgentPageQuery`：默认第一页、每页 20 条，每页最多 100 条；按 `created_at DESC, id DESC` 排序，响应在统一 `data` 包装中包含 `records`、`total`、`current`、`size`、`pages`。项目记录包含 `id`、`projectName`、`description`、`userId`、`createdAt`，无记录时返回空列表。

新库 V2 已包含独立权限 `agent:project:page` 及超级管理员授权（当前共 21 个权限）。已完成原 V2 或 V3 的数据库需手动执行 `sql/V4__add_agent_project_page_permission.sql`；若原库尚无项目表，先执行 V3，再执行 V4。执行前核对脚本中的固定种子 ID，执行后须撤销受影响用户 Redis 会话或重新登录；其他角色须显式授权。此处仅提供升级文件，没有自动修改数据库。

九个项目及对话权限归属“用户前台”目录，接口与操作按钮共用类型 3 节点。V2 为固定种子 `super_admin` 角色逐条授予全部 21 个权限；已有库通过 V3 授予原 18 个权限，再由 V4 增加项目分页权限、V5 增加修改项目和重命名对话权限，重复执行授权语句不会增加重复关系。后续所有新增权限也必须在对应版本 SQL 中同步授予超级管理员角色，普通角色仍须单独授权；执行后须撤销受影响用户 Redis 会话或重新登录。未登录返回 401，已登录但未授权返回 403；服务专用 HTTP 业务方法也保留独立权限检查。

修改项目使用 `PUT /user/agent/projects/{projectId}`，JSON 为 `{"projectName":"新项目名","description":"新介绍"}`；名称必填且最多 128 个字符，介绍最多 2000 个字符，省略、null 或空字符串均清空为数据库 NULL。名称去除首尾空白后保存，允许重名，只更新名称和介绍。

重命名对话使用 `PUT /user/agent/chats/{chatId}/name`，JSON 为 `{"chatName":"新对话名"}`；名称必填且最多 128 个字符，去除首尾空白后保存，允许重名。项目、无项目及归档对话均支持重命名，原有项目归属、归档状态和创建时间不变。两个接口均按 JWT 用户 ID 和资源 ID 联合更新，成功返回统一结果包装中的提示文本；他人资源和不存在资源返回相同业务错误。

已有数据库需手动执行 `sql/V5__add_agent_update_permissions.sql`，新增 `agent:project:update` 与 `agent:chat:rename` 两个独立权限，并逐条授予固定种子超级管理员角色；新库 V2 已包含相同权限。执行前核对固定种子 ID，执行后撤销受影响用户 Redis 会话或要求重新登录。未自动执行数据库脚本。

### 聊天创建、归档、恢复和移动

新增权限集中在独立文件 `sql/V6__add_agent_chat_lifecycle_permissions.sql`，不写回 V2；新库也须在 V2 后执行 V6。已有库须先确保项目及聊天表存在，缺失时先执行 V3；V4 和 V5 仍分别维护前述分页和修改权限。V6 每条 INSERT 仅插入一行，新增四个独立权限并逐条授予固定种子超级管理员，其他角色需显式授权。执行前核对固定种子 ID，执行后撤销受影响用户 Redis 会话或要求重新登录。

| 方法 | 路径 | 请求体 | 权限 |
| --- | --- | --- | --- |
| POST | `/user/agent/chats` | `{"chatName":"新聊天","projectId":null}` | `agent:chat:create` |
| PUT | `/user/agent/chats/{chatId}/archive` | 无 | `agent:chat:archive` |
| PUT | `/user/agent/chats/{chatId}/restore` | 无 | `agent:chat:restore` |
| PUT | `/user/agent/chats/{chatId}/move` | `{"projectId":123}` 或 `{"projectId":null}` | `agent:chat:move` |

创建时名称必填、最多 128 个字符，去除首尾空白后保存，允许重名；项目 ID 可省略或为 null，表示无项目聊天，否则必须为本人有效项目。类型由服务端推导，初始归档状态固定为未归档，返回持久化后的聊天实体和数据库创建时间。客户端无法设置所有者、类型或初始归档状态。

归档与恢复均幂等，仅修改本人聊天的归档状态，不改变项目归属。移动支持本人项目间移动、无项目聊天移入项目，以及移出为无项目聊天（目标项目 ID 省略或 null）；移动请求体必须提供。项目 ID 与类型在同一次 UPDATE 中同步更新，名称、归档状态和创建时间保持不变。移入目标项目时先校验所有权并在事务内锁定项目行，与项目删除和创建采用相同锁顺序，防止产生孤立关联。归档、恢复和移动成功时返回统一包装中的提示文本。

原有项目及无项目分页继续包含归档记录，归档分页仅展示已归档聊天。所有接口从验签 JWT 获取用户 ID，未登录为 401、缺少对应独立权限为 403，资源不存在或非本人所有时返回相同业务错误。本次仅提供 SQL 文件，不自动修改数据库。

### 初始化插入语句规范

静态初始化和升级数据统一采用单行插入语句 `INSERT INTO ... VALUES (...);`，每条 INSERT 只插入一行，保留 VALUES 关键字，不使用多组 VALUES 批量插入；直接列出菜单父 ID、用户角色关联和超级管理员授权（V2 共 21 条，V3 原 18 条，V4 补充 1 条，V5 补充 2 条）。V2、V3、V4 和 V5 不使用 `INSERT ... SELECT` 或 JOIN 组装静态数据；授权关系 ID 沿用原有确定值，重复关系通过 `ON CONFLICT` 跳过。后续新增权限时，必须在对应版本脚本中同步追加独立的超级管理员授权 INSERT 语句。

所有关联使用 V2 中的固定种子 ID。已有数据库执行前须核对同编码目录、菜单、权限以及 `admin`、`super_admin` 的 ID 与脚本一致；自定义 ID 须先调整脚本中的关联值。脚本仅授权明确列出的权限，自定义权限需额外列出授权行，不再扫描权限表。
