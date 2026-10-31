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

脚本创建 `chomy_ai`，所有者为 `root`；该角色须已存在，执行用户需要 `CREATEDB` 权限。脚本仅首次手动执行，不自动执行，也不应放在事务中执行。现有数据库不会因脚本修改而变更所有者。

`compose.yml` 可用于启动本地 PostgreSQL、Redis；若使用本地依赖，需要在对应 Nacos 配置中调整 `db.*`、`redis.*`，与 Compose 的账号及密码保持一致。

## 代码约定及构建

- 启动类：`com.chomy.ai.backend.ChomyAiBackendApplication`。
- Java 日志使用 SLF4J，通过 Lombok `@Slf4j` 自动生成 `log` 字段；Maven 已配置注解处理器，Lombok 不打入运行包。日志使用 `{}` 占位符并保留必要的异常堆栈，不输出密码及令牌。Lombok 接入方式参考 [官方 Maven 配置说明](https://projectlombok.org/setup/maven)。
- MyBatis-Plus 已配置 PostgreSQL 分页插件，单页最多 500 条；Mapper 放在启动类子包并添加 `@Mapper`，继承 `BaseMapper<T>`。XML 放在 `src/main/resources/mapper/`。
- Redis 可直接注入 `StringRedisTemplate`；对象缓存需显式定义序列化方式。
- 项目未创建业务表或业务接口。

```bash
mvn clean verify
java -jar target/chomy-ai-backend-0.0.1-SNAPSHOT.jar
```
