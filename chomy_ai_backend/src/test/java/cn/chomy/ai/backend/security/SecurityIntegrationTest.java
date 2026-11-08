package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.controller.user.AgentProjectController;
import cn.chomy.ai.backend.controller.user.AgentChatController;
import cn.chomy.ai.backend.controller.user.SessionMessageController;
import cn.chomy.ai.backend.service.SessionMessageService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.chomy.ai.backend.service.AgentProjectService;
import cn.chomy.ai.backend.service.AgentChatService;
import cn.chomy.ai.backend.dto.agent.CreateAgentProjectRequest;
import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.enums.AgentPermission;
import cn.chomy.ai.backend.dto.agent.UpdateAgentProjectRequest;
import cn.chomy.ai.backend.dto.agent.CreateAgentChatRequest;
import cn.chomy.ai.backend.dto.agent.MoveAgentChatRequest;
import cn.chomy.ai.backend.dto.agent.GenerateAgentChatNameRequest;
import cn.chomy.ai.backend.dto.agent.AgentChatNameResponse;
import cn.chomy.ai.backend.dto.agent.RenameAgentChatRequest;
import org.springframework.security.access.AccessDeniedException;


import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import cn.chomy.ai.backend.enums.*;
import cn.chomy.ai.backend.config.CorsConfig;
import cn.chomy.ai.backend.dto.admin.*;
import cn.chomy.ai.backend.service.AdminUserService;
import cn.chomy.ai.backend.controller.admin.UserController;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.chomy.ai.backend.config.SecurityConfig;
import cn.chomy.ai.backend.config.WebMvcConfig;
import cn.chomy.ai.backend.controller.HealthController;
import cn.chomy.ai.backend.controller.user.LoginController;
import cn.chomy.ai.backend.controller.user.UserInfoController;
import cn.chomy.ai.backend.common.web.*;
import cn.chomy.ai.backend.entity.*;
import cn.chomy.ai.backend.mapper.*;
import cn.chomy.ai.backend.service.impl.UserServiceImpl;
import cn.chomy.ai.backend.service.impl.LoginServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实 Spring Security 链、BCrypt、JWT 编解码和控制器验证用户端完整认证授权流程。
 * 数据库和 Redis 使用内存替身，避免测试依赖真实账号、外部服务或建表状态。
 *
 * @author chomy
 */
class SecurityIntegrationTest {
    /** 独立 Web 上下文，包含真实安全过滤器。 */
    private AnnotationConfigWebApplicationContext context;
    /** HTTP 层测试入口。 */
    private MockMvc mvc;
    /** 内存数据及缓存替身。 */
    private TestStore store;
    /** 与正式接口一致的 JSON 编解码器。 */
    private ObjectMapper json;

    /** 初始化真实组件及 SQL 元数据，只替换外部持久化边界。 */
    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(User.class, Role.class, Permission.class, UserRole.class, RolePermission.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "security-test"), type);
        }
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("security-test", Map.of(
                "security.jwt.secret-base64", Base64.getEncoder().encodeToString(new byte[32]))));
        context.register(TestConfig.class);
        context.refresh();
        store = context.getBean(TestStore.class);
        json = context.getBean(ObjectMapper.class);
        store.user.setPasswordHash(context.getBean(PasswordEncoder.class).encode("OriginalPass123!"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new RequestTimingFilter(), new UserContextCleanupFilter(), new CorsConfig().corsFilterRegistration().getFilter())
                .apply(springSecurity()).build();
    }

    /** 关闭每个测试创建的上下文，避免共享安全状态。 */
    @AfterEach
    void tearDown() {
        try {
            // 完整安全链的成功、401、403、503 分支都必须释放容器线程上的用户。
            assertThat(UserContext.get()).isNull();
        } finally {
            UserContext.clear();
            if (context != null) { context.close(); }
        }
    }

    /** 登录返回 JWT、资料和角色权限，并将同一授权快照写入有有效期的 Redis 会话。 */
    @Test
    void loginCachesAndReturnsRolesAndPermissions() throws Exception {
        String token = login();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(store.cache).hasSize(3);
        String cached = String.join(" ", store.cache.values());
        assertThat(cached).contains("roleCode", "demo:read", "passwordFingerprint", "profile", "原签名")
                .doesNotContain("passwordHash", "OriginalPass123!");
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        verify(store.roles, times(1)).selectList(any(Wrapper.class));
    }

    /** 请求从 Redis 恢复登录快照，伪造用户头无效；旧格式或错配资料必须要求重新登录。 */
    @Test
    void restoresUserContextAndRejectsIncompleteCachedProfile() throws Exception {
        String token = login();
        mvc.perform(get("/test/context").header("Authorization", "Bearer " + token).header("X-User-Id", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("1"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.signature").value("原签名"));
        assertThat(UserContext.get()).isNull();
        String key = store.cache.keySet().stream().filter(value -> value.startsWith("chomy:auth:session:")).findFirst().orElseThrow();
        var cached = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(store.cache.get(key));
        cached.withObject("/profile").put("id", "999");
        store.cache.put(key, json.writeValueAsString(cached));
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        cached.remove("profile");
        store.cache.put(key, json.writeValueAsString(cached));
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    /** 匿名访问个人接口返回 401，公开健康检查正常。 */
    @Test
    void requiresAuthenticationForPersonalEndpoints() throws Exception {
        mvc.perform(get("/user/info")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.durationMs").isNumber());
        mvc.perform(get("/health/health")).andExpect(status().isOk());
    }

    /** 错误密码、未知用户、冻结及未激活用户均不能获取 Token。 */
    @Test
    void rejectsWrongCredentialsAndUnavailableAccounts() throws Exception {
        mvc.perform(post("/user/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/user/login").contentType("application/json")
                        .content("{\"username\":\"missing\",\"password\":\"OriginalPass123!\"}"))
                .andExpect(status().isUnauthorized());
        store.user.setStatus(UserStatus.FROZEN);
        attemptLogin().andExpect(status().isUnauthorized());
        store.user.setStatus(UserStatus.NORMAL); store.user.setActived(ActivationStatus.INACTIVE);
        attemptLogin().andExpect(status().isUnauthorized());
        assertThat(store.cache).isEmpty();
    }

    /** 数据库未知枚举值可能被类型处理器映射为 null，不能因此回退为正常或已激活状态。 */
    @Test
    void rejectsUnknownAccountState() throws Exception {
        store.user.setStatus(null);
        attemptLogin().andExpect(status().isUnauthorized());
        store.user.setStatus(UserStatus.NORMAL);
        store.user.setActived(null);
        attemptLogin().andExpect(status().isUnauthorized());
        assertThat(store.cache).isEmpty();
    }

    /** 有效 JWT 必须同时存在 Redis 会话，篡改、过期、错误签发者和无缓存均拒绝。 */
    @Test
    void rejectsTamperedExpiredAndUncachedTokens() throws Exception {
        String token = login();
        int signature = token.lastIndexOf('.') + 1;
        String bad = token.substring(0, signature) + (token.charAt(signature) == 'A' ? "B" : "A") + token.substring(signature + 1);
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized());
        var claims = JwtClaimsSet.builder().issuer(JwtSessionService.ISSUER).subject("1").id("expired")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(300)).build();
        String expired = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        store.cache.clear();
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    /** 密码修改验证原密码并保存哈希；当前及其他设备旧 Token 随即失效。 */
    @Test
    void passwordChangeInvalidatesAllOldTokens() throws Exception {
        String first = login(); String second = login();
        mvc.perform(put("/user/password").header("Authorization", "Bearer " + first).contentType("application/json")
                        .content("{\"oldPassword\":\"bad\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/user/password").header("Authorization", "Bearer " + first).contentType("application/json")
                        .content("{\"oldPassword\":\"OriginalPass123!\",\"newPassword\":\"NewPassword123!\"}"))
                .andExpect(status().isOk());
        assertThat(context.getBean(PasswordEncoder.class).matches("NewPassword123!", store.user.getPasswordHash())).isTrue();
        for (String token : List.of(first, second)) {
            mvc.perform(get("/user/info").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/user/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"NewPassword123!\"}"))
                .andExpect(status().isOk());
    }

    /** 个人资料白名单更新不能更改身份、手机号、角色及状态，可空资料可以主动清空。 */
    @Test
    void profileUpdateCannotEscalatePrivileges() throws Exception {
        String token = login();
        mvc.perform(put("/user/info").header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"id\":2,\"username\":\"admin\",\"phone\":\"changed\",\"status\":1,\"roleId\":1,"
                                + "\"nickname\":\"新昵称\",\"gender\":2,\"signature\":null,\"avatarUrl\":\"https://example.com/a.png\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("1"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.gender").value(2));
        assertThat(store.user.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(store.user.getPhone()).isEqualTo("13800000000");
        assertThat(store.user.getSignature()).isNull();
        mvc.perform(put("/user/info").header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"nickname\":\"昵称\",\"gender\":9}"))
                .andExpect(status().isBadRequest());
    }

    /** 冻结会阻断已签发 Token，退出只清理当前会话，其他设备仍保持有效。 */
    @Test
    void logoutAndFreezeRevokeAccess() throws Exception {
        String first = login(); String second = login();
        mvc.perform(post("/user/logout").header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        store.user.setStatus(UserStatus.FROZEN);
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + second)).andExpect(status().isUnauthorized());
    }

    /** 接口权限、HTTP 方法、禁用标志及方法级权限均在后端生效，未知接口默认拒绝。 */
    @Test
    void enforcesRequestAndMethodPermissions() throws Exception {
        String token = login();
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/test/protected").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/unknown").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/test/method").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        store.permission.setStatus(EnableStatus.DISABLED);
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    /** 没有角色的用户可以登录和维护自身资料，但不能访问受 RBAC 保护的业务接口。 */
    @Test
    void emptyRolesDoNotGrantAllPermissions() throws Exception {
        when(store.userRoles.selectList(any(Wrapper.class))).thenReturn(List.of());
        String token = login();
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        verify(store.roles, never()).selectList(any(Wrapper.class));
    }

    /** Redis 故障必须拒绝认证且返回统一 503，不回退到只验 JWT 签名的弱化模式。 */
    @Test
    void failsClosedWhenRedisIsUnavailable() throws Exception {
        String token = login();
        when(store.values.get(anyString())).thenThrow(new RedisConnectionFailureException("test-only"));
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
    }

    /** Redis 会话写入失败时登录返回 503，不能先返回 JWT 再忽略缓存失败。 */
    @Test
    void rejectsLoginWhenCacheWriteFails() throws Exception {
        doThrow(new RedisConnectionFailureException("test-only")).when(store.values)
                .set(anyString(), anyString(), any(Duration.class));
        attemptLogin().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(503));
        assertThat(store.cache).isEmpty();
    }

    /** 精确资源的权限要求高于宽泛路径，不能用 /test/** 的授权绕过精确接口权限。 */
    @Test
    void exactPermissionOverridesBroadPattern() throws Exception {
        String token = login();
        Permission exact = new Permission();
        exact.setApiPath("/test/protected"); exact.setHttpMethod("GET"); exact.setStatus(EnableStatus.ENABLED);
        exact.setPermissionCode("more:restricted"); exact.setPermissionType(PermissionType.API);
        when(store.permissions.selectList(any(Wrapper.class))).thenReturn(List.of(store.permission, exact));
        mvc.perform(get("/test/protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 授权变更业务撤销用户会话后，原来的已签发 JWT 不再允许使用旧授权缓存。 */
    @Test
    void invalidatesPermissionSnapshotWhenUserSessionsRevoked() throws Exception {
        String token = login();
        context.getBean(JwtSessionService.class).revokeUserSessions(1L);
        mvc.perform(get("/user/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** 密码和资料字段遵循服务端规则，拒绝短密码、相同密码及非 HTTP 头像地址。 */
    @Test
    void validatesPasswordAndProfileRules() throws Exception {
        String token = login();
        for (String password : List.of("short", "OriginalPass123!", "中".repeat(25))) {
            String body = json.writeValueAsString(Map.of("oldPassword", "OriginalPass123!", "newPassword", password));
            mvc.perform(put("/user/password").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/user/info").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"nickname\":\"昵称\",\"gender\":1,\"avatarUrl\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
    }

    /** HTTP 请求也必须严格使用已定义的整数性别编码，不接受 Jackson 的小数截断或字符串转换。 */
    @Test
    void rejectsInvalidGenderCodesAtHttpBoundary() throws Exception {
        String token = login();
        for (String gender : List.of("99", "1.5", "\"1\"", "\"MALE\"", "null")) {
            mvc.perform(put("/user/info").header("Authorization", "Bearer " + token).contentType("application/json")
                    .content("{\"nickname\":\"昵称\",\"gender\":" + gender + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(store.user.getGender()).isEqualTo(Gender.UNKNOWN);
    }

    /** 用户管理全部九个接口都必须与 V2 种子里的路径、方法和独立权限码对应。 */
    @Test
    void adminEndpointsMatchPermissionSeedAndDelegate() throws Exception {
        grantAdminPermissions(UserManagementPermission.values());
        when(store.adminUsers.create(any(AdminCreateUserRequest.class))).thenReturn(AdminUserView.from(store.user));
        when(store.adminUsers.detail(anyLong())).thenReturn(AdminUserView.from(store.user));
        when(store.adminUsers.page(any(AdminUserQuery.class))).thenReturn(new Page<AdminUserView>(1, 20));
        String token = login();
        mvc.perform(post("/admin/users").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"bob\",\"nickname\":\"新用户\",\"phone\":\"13800000002\",\"password\":\"InitialPass123!\",\"createdBy\":999,\"createType\":1}"))
                .andExpect(status().isOk());
        verify(store.adminUsers).create(any(AdminCreateUserRequest.class));
        for (String action : List.of("activate", "freeze", "unfreeze")) {
            mvc.perform(put("/admin/users/2/" + action).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        mvc.perform(put("/admin/users/2/password").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"newPassword\":\"ChangedPass123!\"}")).andExpect(status().isOk());
        mvc.perform(put("/admin/users/2").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"bob\",\"nickname\":\"昵称\",\"phone\":\"13800000002\",\"gender\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/users/2").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(delete("/admin/users/2").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token)
                .param("status", "1").param("actived", "0").param("gender", "2").param("current", "2"))
                .andExpect(status().isOk());
        var query = org.mockito.ArgumentCaptor.forClass(AdminUserQuery.class);
        verify(store.adminUsers).page(query.capture());
        assertThat(query.getValue().getStatus()).isEqualTo(UserStatus.FROZEN);
        assertThat(query.getValue().getActived()).isEqualTo(ActivationStatus.INACTIVE);
        assertThat(query.getValue().getGender()).isEqualTo(Gender.FEMALE);
        assertThat(query.getValue().getCurrent()).isEqualTo(2);
        verify(store.adminUsers).freeze(2L);
        verify(store.adminUsers).delete(2L);
    }

    /** 只读接口权限不能执行创建或删除，共用操作权限也不能越权到其他接口。 */
    @Test
    void adminReadPermissionCannotMutateUsers() throws Exception {
        grantAdminPermissions(UserManagementPermission.PAGE, UserManagementPermission.DETAIL);
        when(store.adminUsers.page(any(AdminUserQuery.class))).thenReturn(new Page<AdminUserView>(1, 20));
        String token = login();
        mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/admin/users").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"bob\",\"nickname\":\"昵称\",\"phone\":\"13800000002\",\"password\":\"InitialPass123!\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/admin/users/2").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        verify(store.adminUsers, never()).create(any());
        verify(store.adminUsers, never()).delete(anyLong());
    }

    /** 即使绕过 HTTP 请求级匹配，用户管理 Service 自身仍要求独立操作权限。 */
    @Test
    void adminServiceHasMethodLevelAuthorization() throws Exception {
        grantAdminPermissions(UserManagementPermission.PAGE);
        String token = login();
        var authentication = context.getBean(JwtSessionService.class).authenticate(context.getBean(JwtDecoder.class).decode(token));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(AdminUserService.class).delete(2L))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    /** 无效分页及枚举参数必须在 MVC 参数校验阶段拒绝，不能进入数据查询。 */
    @Test
    void validatesAdminPaginationAndEnums() throws Exception {
        grantAdminPermissions(UserManagementPermission.PAGE);
        String token = login();
        for (String[] pair : List.of(new String[]{"size", "101"}, new String[]{"current", "0"},
                new String[]{"status", "9"}, new String[]{"gender", "FEMALE"})) {
            mvc.perform(get("/admin/users").header("Authorization", "Bearer " + token).param(pair[0], pair[1]))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("查询参数 %s=%s 应被拒绝", pair[0], pair[1]).isEqualTo(400));
        }
        verify(store.adminUsers, never()).page(any());
    }

    /** 仅有菜单不获得操作权限；菜单不隐式授予合并后的任何操作节点。 */
    @Test
    void adminMenuDoesNotGrantApiPermission() throws Exception {
        List<Permission> all = adminResources();
        Permission button = new Permission(); button.setId(99L); button.setPermissionCode("user:manage");
        button.setPermissionType(PermissionType.MENU); button.setStatus(EnableStatus.ENABLED);
        when(store.permissions.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<Permission> wrapper = call.getArgument(0);
            return wrapper.getSqlSegment().contains("permission_type") ? all : List.of(button);
        });
        String token = login();
        mvc.perform(post("/admin/users").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"bob\",\"nickname\":\"昵称\",\"phone\":\"13800000002\",\"password\":\"InitialPass123!\"}"))
                .andExpect(status().isForbidden());
    }

    /** 同一个接口权限既在登录结果中供前端控制按钮，也能直接通过后端操作校验，无需第二份授权。 */
    @Test
    void oneApiPermissionEnablesButtonAndOperation() throws Exception {
        grantAdminPermissions(UserManagementPermission.CREATE);
        var result = attemptLogin().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions[0].permissionCode").value("user:update:create"))
                .andExpect(jsonPath("$.data.permissions[0].permissionType").value(PermissionType.API.getCode()))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("user:button:");
        String token = json.readTree(body).path("data").path("accessToken").asText();
        mvc.perform(post("/admin/users").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"username\":\"bob\",\"nickname\":\"昵称\",\"phone\":\"13800000002\",\"password\":\"InitialPass123!\"}"))
                .andExpect(status().isOk());
        verify(store.adminUsers).create(any(AdminCreateUserRequest.class));
        // 合并只发生在同一个操作内，拥有创建权限不能借此修改用户密码。
        mvc.perform(put("/admin/users/2/password").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"newPassword\":\"ChangedPass123!\"}")).andExpect(status().isForbidden());
    }

    /** 九个项目及对话接口必须与真实 SQL 权限一一对应，用户 ID 来自 JWT 而非表单。 */
    @Test
    void agentEndpointsMatchSeedAndDelegateBusinessArguments() throws Exception {
        grantAgentPermissions(AgentPermission.values());
        when(store.agentProjects.pageProjects(any())).thenReturn(new Page<>());
        when(store.agentChats.projectChats(anyLong(), any())).thenReturn(new Page<>());
        when(store.agentChats.standaloneChats(any())).thenReturn(new Page<>());
        when(store.agentChats.archivedChats(any())).thenReturn(new Page<>());
        when(store.agentChats.deleteChat(anyLong())).thenReturn("对话已删除");
        when(store.agentProjects.deleteProject(anyLong())).thenReturn("项目及其全部对话已删除");
        String token = login();
        // 故意附带其他用户 ID，Controller 只委托表单；Service 执行时仍能读取真实用户 1。
        doAnswer(call -> {
            assertThat(UserContext.userId()).isEqualTo(1L);
            return null;
        }).when(store.agentProjects).createProject(any(CreateAgentProjectRequest.class));
        mvc.perform(post("/user/agent/projects").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"projectName\":\"项目\",\"userId\":999}"))
                .andExpect(status().isOk());
        verify(store.agentProjects).createProject(any(CreateAgentProjectRequest.class));
        // 查询串中的伪造 userId 不作为业务参数传递；合法页码应完整委托给 Service。
        mvc.perform(get("/user/agent/projects?userId=999&current=2&size=10").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isArray());
        verify(store.agentProjects).pageProjects(argThat(query -> query.getCurrent() == 2L && query.getSize() == 10L));
        mvc.perform(get("/user/agent/projects/10/chats").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        verify(store.agentChats).projectChats(eq(10L), any(AgentPageQuery.class));
        mvc.perform(get("/user/agent/chats/standalone").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        verify(store.agentChats).standaloneChats(any(AgentPageQuery.class));
        mvc.perform(get("/user/agent/chats/archived").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        verify(store.agentChats).archivedChats(any(AgentPageQuery.class));
        mvc.perform(delete("/user/agent/chats/100").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        verify(store.agentChats).deleteChat(100L);
        mvc.perform(delete("/user/agent/projects/10").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        verify(store.agentProjects).deleteProject(10L);
    }

    /** 未登录返回 401，已登录但未授权返回 403，拥有查询权限也不能删除数据。 */
    @Test
    void agentEndpointsRequireIndependentPermissions() throws Exception {
        mvc.perform(get("/user/agent/chats/archived")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/agent/projects")).andExpect(status().isUnauthorized());
        grantAgentPermissions(AgentPermission.ARCHIVED_CHATS);
        String token = login();
        mvc.perform(post("/user/agent/projects").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"projectName\":\"项目\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/user/agent/projects").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(delete("/user/agent/projects/10").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(delete("/user/agent/chats/100").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        verifyNoInteractions(store.agentProjects, store.agentChats);
        // 非 HTTP 入口仍经过接口声明的服务层权限，不能以单独查询权限执行删除。
        var authentication = context.getBean(JwtSessionService.class).authenticate(context.getBean(JwtDecoder.class).decode(token));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(AgentChatService.class).deleteChat(100L))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    /** 创建权限不能隐式授予项目分页，分页权限也不能隐式授予项目删除。 */
    @Test
    void projectPagePermissionIsIndependent() throws Exception {
        grantAgentPermissions(AgentPermission.PROJECT_CREATE);
        String createToken = login();
        mvc.perform(get("/user/agent/projects").header("Authorization", "Bearer " + createToken))
                .andExpect(status().isForbidden());
        grantAgentPermissions(AgentPermission.PROJECT_PAGE);
        when(store.agentProjects.pageProjects(any())).thenReturn(new Page<>());
        String pageToken = login();
        mvc.perform(get("/user/agent/projects").header("Authorization", "Bearer " + pageToken)).andExpect(status().isOk());
        mvc.perform(delete("/user/agent/projects/10").header("Authorization", "Bearer " + pageToken))
                .andExpect(status().isForbidden());
        verify(store.agentProjects, never()).deleteProject(anyLong());
    }

    /** 修改权限使用真实 SQL 路由，认证用户不能被表单中的伪造所有者替换。 */
    @Test
    void updateEndpointsUseJwtOwnerAndIndependentPermissions() throws Exception {
        grantAgentPermissions(AgentPermission.PROJECT_UPDATE);
        when(store.agentProjects.updateProject(anyLong(), any())).thenReturn("项目已修改");
        String projectToken = login();
        mvc.perform(put("/user/agent/projects/10").header("Authorization", "Bearer " + projectToken)
                .contentType("application/json").content("{\"projectName\":\"新名称\",\"description\":null,\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("项目已修改"));
        verify(store.agentProjects).updateProject(eq(10L), eq(new UpdateAgentProjectRequest("新名称", null)));
        mvc.perform(put("/user/agent/chats/100/name").header("Authorization", "Bearer " + projectToken)
                .contentType("application/json").content("{\"chatName\":\"新对话\"}"))
                .andExpect(status().isForbidden());
        grantAgentPermissions(AgentPermission.CHAT_RENAME);
        when(store.agentChats.renameChat(anyLong(), any())).thenReturn("对话已重命名");
        String chatToken = login();
        mvc.perform(put("/user/agent/chats/100/name").header("Authorization", "Bearer " + chatToken)
                .contentType("application/json").content("{\"chatName\":\"新对话\",\"userId\":999,\"projectId\":999,\"archived\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("对话已重命名"));
        verify(store.agentChats).renameChat(eq(100L), eq(new RenameAgentChatRequest("新对话")));
        mvc.perform(put("/user/agent/projects/10").header("Authorization", "Bearer " + chatToken)
                .contentType("application/json").content("{\"projectName\":\"新名称\"}"))
                .andExpect(status().isForbidden());
    }

    /** 未登录和仅有读取权限时拒绝修改，非法表单与 ID 不进入业务服务。 */
    @Test
    void updateEndpointsRejectUnauthorizedAndInvalidInputs() throws Exception {
        for (String path : List.of("/user/agent/projects/10", "/user/agent/chats/100/name")) {
            mvc.perform(put(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        grantAgentPermissions(AgentPermission.PROJECT_PAGE, AgentPermission.PROJECT_CHATS);
        String readToken = login();
        for (String path : List.of("/user/agent/projects/10", "/user/agent/chats/100/name")) {
            mvc.perform(put(path).header("Authorization", "Bearer " + readToken)
                    .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        }
        // 服务接口仍保留方法级鉴权，非 HTTP 调用也不能借读取权限执行修改。
        var authentication = context.getBean(JwtSessionService.class).authenticate(context.getBean(JwtDecoder.class).decode(readToken));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(AgentProjectService.class)
                    .updateProject(10L, new UpdateAgentProjectRequest("名称", null))).isInstanceOf(AccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(AgentChatService.class)
                    .renameChat(100L, new RenameAgentChatRequest("名称"))).isInstanceOf(AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        grantAgentPermissions(AgentPermission.PROJECT_UPDATE, AgentPermission.CHAT_RENAME);
        String token = login();
        for (String name : List.of("", "  ", "a".repeat(129))) {
            mvc.perform(put("/user/agent/projects/10").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(json.writeValueAsString(new UpdateAgentProjectRequest(name, null))))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/user/agent/chats/100/name").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(json.writeValueAsString(new RenameAgentChatRequest(name))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/user/agent/projects/10").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(new UpdateAgentProjectRequest("名称", "a".repeat(2001)))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/user/agent/projects/0").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"projectName\":\"名称\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/user/agent/chats/0/name").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"名称\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(store.agentProjects, store.agentChats);
    }

    /** 新生命周期接口按真实 V6 授权，用户 ID 固定来自 JWT，派生字段不进入表单。 */
    @Test
    void chatLifecycleUsesJwtOwnerAndWhitelist() throws Exception {
        grantAgentPermissions(AgentPermission.CHAT_CREATE, AgentPermission.CHAT_ARCHIVE,
                AgentPermission.CHAT_RESTORE, AgentPermission.CHAT_MOVE);
        AgentChat created = new AgentChat(); created.setId(100L); created.setUserId(1L);
        created.setChatName("新聊天"); created.setType(AgentChatType.STANDALONE);
        created.setArchived(AgentChatArchiveStatus.UNARCHIVED);
        when(store.agentChats.createChat(any(CreateAgentChatRequest.class))).thenReturn(created);
        when(store.agentChats.archiveChat(anyLong())).thenReturn("对话已归档");
        when(store.agentChats.restoreChat(anyLong())).thenReturn("对话已恢复");
        when(store.agentChats.moveChat(anyLong(), any())).thenReturn("对话已移动");
        String token = login();
        mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"新聊天\",\"userId\":999,\"type\":1,\"archived\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("100"))
                .andExpect(jsonPath("$.data.archived").value(0));
        verify(store.agentChats).createChat(eq(new CreateAgentChatRequest("新聊天", null)));
        mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"项目聊天\",\"projectId\":10}"))
                .andExpect(status().isOk());
        verify(store.agentChats).createChat(eq(new CreateAgentChatRequest("项目聊天", 10L)));
        for (String action : List.of("archive", "restore")) {
            mvc.perform(put("/user/agent/chats/100/" + action).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
        verify(store.agentChats).archiveChat(100L); verify(store.agentChats).restoreChat(100L);
        mvc.perform(put("/user/agent/chats/100/move").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"projectId\":20,\"userId\":999,\"archived\":0}"))
                .andExpect(status().isOk());
        verify(store.agentChats).moveChat(eq(100L), eq(new MoveAgentChatRequest(20L)));
        mvc.perform(put("/user/agent/chats/100/move").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"projectId\":null}" )).andExpect(status().isOk());
        verify(store.agentChats).moveChat(eq(100L), eq(new MoveAgentChatRequest(null)));
        // 前端用十进制字符串传递雪花 ID，Jackson 绑定 Long 时必须保留超出 JS 安全整数范围的精度。
        mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"大整数项目聊天\",\"projectId\":\"970000000000001041\"}"))
                .andExpect(status().isOk());
        verify(store.agentChats).createChat(eq(new CreateAgentChatRequest("大整数项目聊天", 970000000000001041L)));
        mvc.perform(put("/user/agent/chats/100/move").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"projectId\":\"970000000000001041\"}"))
                .andExpect(status().isOk());
        verify(store.agentChats).moveChat(eq(100L), eq(new MoveAgentChatRequest(970000000000001041L)));
    }

    /** 创建、归档、恢复、移动各自独立授权，归档权限不能授予恢复或移动。 */
    @Test
    void chatLifecycleRequiresIndependentPermissions() throws Exception {
        mvc.perform(post("/user/agent/chats").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        for (String action : List.of("archive", "restore", "move")) {
            mvc.perform(put("/user/agent/chats/100/" + action).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        grantAgentPermissions(AgentPermission.CHAT_ARCHIVE);
        when(store.agentChats.archiveChat(anyLong())).thenReturn("对话已归档");
        String token = login();
        mvc.perform(put("/user/agent/chats/100/archive").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"聊天\"}" )).andExpect(status().isForbidden());
        for (String action : List.of("restore", "move")) {
            mvc.perform(put("/user/agent/chats/100/" + action).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        }
        // 服务方法级鉴权同样独立，非 HTTP 调用不因拥有归档权限获得其他写权限。
        var authentication = context.getBean(JwtSessionService.class).authenticate(context.getBean(JwtDecoder.class).decode(token));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            var service = context.getBean(AgentChatService.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.restoreChat(100L)).isInstanceOf(AccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.moveChat(100L, new MoveAgentChatRequest(null)))
                    .isInstanceOf(AccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createChat(new CreateAgentChatRequest("聊天", null)))
                    .isInstanceOf(AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        verify(store.agentChats, never()).restoreChat(anyLong());
        verify(store.agentChats, never()).moveChat(anyLong(), any());
        verify(store.agentChats, never()).createChat(any(CreateAgentChatRequest.class));
    }

    /** 创建与移动的表单、项目 ID 和路径 ID 必须在 MVC 层校验，失败不调用业务。 */
    @Test
    void chatLifecycleValidatesInputs() throws Exception {
        grantAgentPermissions(AgentPermission.CHAT_CREATE, AgentPermission.CHAT_ARCHIVE,
                AgentPermission.CHAT_RESTORE, AgentPermission.CHAT_MOVE);
        String token = login();
        for (String body : List.of("{}", "null", "{\"chatName\":\" \"}",
                json.writeValueAsString(new CreateAgentChatRequest("a".repeat(129), null)),
                "{\"chatName\":\"聊天\",\"projectId\":0}")) {
            mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String body : List.of("null", "{\"projectId\":-1}")) {
            mvc.perform(put("/user/agent/chats/100/move").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/user/agent/chats/100/move").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        for (String action : List.of("archive", "restore", "move")) {
            mvc.perform(put("/user/agent/chats/0/" + action).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store.agentChats);
    }

    /** 名称生成独立鉴权并传递 JWT 身份，响应结构固定为 data.chatName。 */
    @Test
    void generatesChatNameWithIndependentPermission() throws Exception {
        mvc.perform(post("/user/agent/chats/name/generate").contentType("application/json")
                .content("{\"message\":\"帮我学习Java\"}")).andExpect(status().isUnauthorized());
        grantAgentPermissions(AgentPermission.CHAT_CREATE);
        String createToken = login();
        mvc.perform(post("/user/agent/chats/name/generate").header("Authorization", "Bearer " + createToken)
                .contentType("application/json").content("{\"message\":\"帮我学习Java\"}"))
                .andExpect(status().isForbidden());
        // 非 HTTP 调用同样检查方法级权限，创建权限不能隐式授予生成名称权限。
        var authentication = context.getBean(JwtSessionService.class).authenticate(context.getBean(JwtDecoder.class).decode(createToken));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.getBean(AgentChatService.class)
                    .generateName(new GenerateAgentChatNameRequest("文本"))).isInstanceOf(AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        grantAgentPermissions(AgentPermission.CHAT_GENERATE_NAME);
        when(store.agentChats.generateName(any())).thenReturn(new AgentChatNameResponse("帮我学习Java"));
        String token = login();
        mvc.perform(post("/user/agent/chats/name/generate").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"message\":\"帮我学习Java\",\"userId\":999}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatName").value("帮我学习Java"));
        verify(store.agentChats).generateName(eq(new GenerateAgentChatNameRequest("帮我学习Java")));
        // 生成权限仅用于预览名称，不能直接创建聊天。
        mvc.perform(post("/user/agent/chats").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"chatName\":\"名称\"}"))
                .andExpect(status().isForbidden());
    }

    /** 名称生成表单的空文本、空请求体及超长文本在 MVC 层返回 400。 */
    @Test
    void validatesChatNameGenerationInput() throws Exception {
        grantAgentPermissions(AgentPermission.CHAT_GENERATE_NAME);
        String token = login();
        for (String body : List.of("{}", "null", "{\"message\":\" \"}",
                json.writeValueAsString(new GenerateAgentChatNameRequest("字".repeat(2001))))) {
            mvc.perform(post("/user/agent/chats/name/generate").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/user/agent/chats/name/generate").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store.agentChats);
    }

    /** 新会话接口独立鉴权，历史 JSON 继续包装，SSE 帧不得插入 ApiResult 外层。 */
    @Test
    void sessionMessageRoutesUseIndependentPermissionsAndSseFormat() throws Exception {
        mvc.perform(get("/message/10/start")).andExpect(status().isUnauthorized());
        mvc.perform(get("/message/10/messages")).andExpect(status().isUnauthorized());
        grantAgentPermissions(AgentPermission.CHAT_MESSAGES);
        when(store.sessionMessages.messages(anyLong(), any())).thenReturn(new Page<>());
        String historyToken = login();
        mvc.perform(get("/message/10/messages").header("Authorization", "Bearer " + historyToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isArray());
        mvc.perform(get("/message/10/start").header("Authorization", "Bearer " + historyToken)).andExpect(status().isForbidden());
        grantAgentPermissions(AgentPermission.CHAT_SUBSCRIBE);
        String subscribeToken = login();
        SseEmitter emitter = new SseEmitter(0L);
        when(store.sessionMessages.start(10L, "12-0")).thenReturn(emitter);
        var result = mvc.perform(get("/message/10/start").header("Authorization", "Bearer " + subscribeToken)
                        .header("Last-Event-ID", "12-0").accept("text/event-stream"))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        emitter.send(SseEmitter.event().id("13-0").name("message.delta").data(Map.of("delta", "你好")));
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("id:13-0", "event:message.delta", "data:", "你好").doesNotContain("durationMs", "\"code\":200");
        emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        mvc.perform(get("/message/10/messages").header("Authorization", "Bearer " + subscribeToken)).andExpect(status().isForbidden());
        verify(store.sessionMessages).start(10L, "12-0");
    }

    /** 非法 chatId 和页码应在 MVC 层返回 400，不能启动任何订阅。 */
    @Test
    void sessionMessageValidatesChatIdAndPagination() throws Exception {
        grantAgentPermissions(AgentPermission.CHAT_SUBSCRIBE, AgentPermission.CHAT_MESSAGES);
        String token = login();
        mvc.perform(get("/message/0/start").header("Authorization", "Bearer " + token).accept("text/event-stream"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/message/10/messages?size=101").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store.sessionMessages);
    }

    /** 表单和页码校验失败应返回 400，不能将非法参数交给业务层。 */
    @Test
    void agentEndpointsValidateInputs() throws Exception {
        grantAgentPermissions(AgentPermission.values());
        String token = login();
        mvc.perform(post("/user/agent/projects").header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"projectName\":\"  \"}")).andExpect(status().isBadRequest());
        for (String query : List.of("size=101", "size=0", "current=0", "current=2147483648", "current=abc")) {
            // 非法页码或规模在 MVC 层返回 400，不调用服务读取数据。
            mvc.perform(get("/user/agent/projects?" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/user/agent/chats/archived?size=101").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(delete("/user/agent/chats/0").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        verifyNoInteractions(store.agentProjects, store.agentChats);
    }

    /**
     * 从真实 V2/V6/V7 提取全部 Agent 接口资源，只向测试账户授予指定权限。
     * @param granted 本次测试允许的独立权限
     */
    private void grantAgentPermissions(AgentPermission... granted) throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("basedir", "."),
                "sql", "V2__init_rbac_and_user_data.sql"));
        // 新增生命周期权限仅存在独立 V6，测试必须合并实际执行脚本的资源定义。
        sql += java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("basedir", "."),
                "sql", "V6__add_agent_chat_lifecycle_permissions.sql"));
        // 名称生成的独立权限在 V7 中定义，测试读取真实路由以覆盖默认拒绝规则。
        sql += java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("basedir", "."),
                "sql", "V7__add_agent_chat_name_permission.sql"));
        // Chat 级历史与订阅权限在独立 V9 中，与实际部署路由共用定义。
        sql += java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("basedir", "."),
                "sql", "V9__add_session_message_permissions.sql"));
        List<Permission> resources = new ArrayList<>();
        // 使用种子中真实路径和方法，避免测试自行声明一套与部署数据不同的权限。
        var matcher = Pattern.compile("\\((\\d+), \\d+, '(agent:[^']+)', '[^']+', 3, '([^']+)', '([A-Z]+)',").matcher(sql);
        while (matcher.find()) {
            resources.add(api(Long.valueOf(matcher.group(1)), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        assertThat(resources).hasSize(AgentPermission.values().length);
        assertThat(resources.stream().map(Permission::getPermissionCode)).containsExactlyInAnyOrder(
                Arrays.stream(AgentPermission.values()).map(AgentPermission::getCode).toArray(String[]::new));
        Set<String> codes = Arrays.stream(granted).map(AgentPermission::getCode).collect(java.util.stream.Collectors.toSet());
        when(store.permissions.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<Permission> wrapper = call.getArgument(0);
            return wrapper.getSqlSegment().contains("permission_type") ? resources
                    : resources.stream().filter(permission -> codes.contains(permission.getPermissionCode())).toList();
        });
    }

    /** 从真实 V2 SQL 种子提取接口定义，让 HTTP 测试与实际导入权限数据保持一致。 */
    private List<Permission> adminResources() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("basedir", "."),
                "sql", "V2__init_rbac_and_user_data.sql"));
        List<Permission> result = new ArrayList<>();
        // 完整 VALUES 行显式列出父 ID 和类型 3，九个接口/按钮共用节点无独立按钮编码。
        Pattern operations = Pattern.compile("\\((\\d+), \\d+, '(user:(?:update|get):[^']+)', '[^']+', 3, '([^']+)', '([A-Z]+)',");
        var matcher = operations.matcher(sql);
        while (matcher.find()) { result.add(api(Long.valueOf(matcher.group(1)), matcher.group(2), matcher.group(3), matcher.group(4))); }
        assertThat(result).hasSize(UserManagementPermission.values().length);
        assertThat(result.stream().map(Permission::getPermissionCode)).containsExactlyInAnyOrder(
                Arrays.stream(UserManagementPermission.values()).map(UserManagementPermission::getCode).toArray(String[]::new));
        return result;
    }

    /** 为当前虚构账户分配指定接口权限，接口元数据仍保留完整定义用于默认拒绝校验。 */
    private void grantAdminPermissions(UserManagementPermission... granted) throws Exception {
        List<Permission> resources = adminResources();
        Set<String> codes = Arrays.stream(granted).map(UserManagementPermission::getCode).collect(java.util.stream.Collectors.toSet());
        when(store.permissions.selectList(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<Permission> wrapper = call.getArgument(0);
            return wrapper.getSqlSegment().contains("permission_type") ? resources
                    : resources.stream().filter(permission -> codes.contains(permission.getPermissionCode())).toList();
        });
    }

    /** 创建与 SQL 中类型和状态一致的接口权限实体，不使用任意管理员绕过标记。 */
    private Permission api(Long id, String code, String path, String method) {
        Permission permission = new Permission(); permission.setId(id); permission.setPermissionCode(code);
        permission.setApiPath(path); permission.setHttpMethod(method); permission.setPermissionType(PermissionType.API);
        permission.setStatus(EnableStatus.ENABLED); return permission;
    }

    /** 跨域预检在安全链之前处理，不要求登录，失败响应也可被跨域前端读取。 */
    @Test
    void supportsCorsWithSecurityEnabled() throws Exception {
        mvc.perform(options("/user/info").header("Origin", "https://frontend.example")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mvc.perform(get("/user/info").header("Origin", "https://frontend.example"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    /** 执行测试账号登录，返回 HTTP 响应断言入口。 */
    private org.springframework.test.web.servlet.ResultActions attemptLogin() throws Exception {
        return mvc.perform(post("/user/login").contentType("application/json")
                .content("{\"username\":\"alice\",\"password\":\"OriginalPass123!\"}"));
    }

    /** 从真实签发结果读取访问令牌，不使用测试框架直接伪造认证主体。 */
    private String login() throws Exception {
        var result = attemptLogin().andExpect(status().isOk()).andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.permissions").isArray()).andExpect(jsonPath("$.durationMs").isNumber())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    /**
     * 真实安全及 Web 配置，只将 Mapper 和 Redis 边界替换为受控内存实现。
     * @author chomy
     */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, WebMvcConfig.class, LoginInterceptor.class, SecurityResponseHandler.class, JwtSessionService.class, RbacUserDetailsService.class,
            RbacAuthorizationManager.class, SessionMessageController.class, AgentProjectController.class, AgentChatController.class, LoginController.class, UserInfoController.class, UserController.class, UserServiceImpl.class, LoginServiceImpl.class,
            GlobalResponseAdvice.class, GlobalExceptionHandler.class, CodeEnumBindingAdvice.class, HealthController.class, ProtectedController.class})
    static class TestConfig {
        /** 创建隔离数据容器，测试间不复用密码及登录会话。 */
        @Bean TestStore testStore() { return new TestStore(); }
        /** 注册生产同类 JSON 规则，忽略未在 DTO 白名单内的字段。 */
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES); }
        /** 用户 Mapper 内存边界。 */
        @Bean UserMapper userMapper(TestStore store) { return store.users; }
        /** 用户角色 Mapper 内存边界。 */
        @Bean UserRoleMapper userRoleMapper(TestStore store) { return store.userRoles; }
        /** 角色 Mapper 内存边界。 */
        @Bean RoleMapper roleMapper(TestStore store) { return store.roles; }
        /** 角色权限 Mapper 内存边界。 */
        @Bean RolePermissionMapper rolePermissionMapper(TestStore store) { return store.rolePermissions; }
        /** 权限 Mapper 内存边界。 */
        @Bean PermissionMapper permissionMapper(TestStore store) { return store.permissions; }
        /** 管理业务替身保留接口上的真实方法级权限，由安全链测试验证调用边界。 */
        @Bean AdminUserService adminUserService(TestStore store) { return store.adminUsers; }
        /** 项目业务替身，保留接口上的真实方法权限检查。 */
        @Bean AgentProjectService agentProjectService(TestStore store) { return store.agentProjects; }
        /** 对话业务替身，保留接口上的真实方法权限检查。 */
        @Bean AgentChatService agentChatService(TestStore store) { return store.agentChats; }
        /** Chat 级会话业务边界，保留接口上真实方法鉴权。 */
        @Bean SessionMessageService sessionMessageService(TestStore store) { return store.sessionMessages; }
        /** Redis 内存边界。 */
        @Bean StringRedisTemplate redis(TestStore store) { return store.redis; }
    }

    /**
     * 只在测试上下文注册的业务接口，验证请求级及方法级鉴权。
     * @author chomy
     */
    @RestController
    static class ProtectedController {
        /** 数据库配置的 GET 接口权限可访问此路径。 */
        @GetMapping("/test/protected") public String read() { return "ok"; }
        /** 返回拦截器绑定的用户资料，证明 Controller 无需接收 JWT。 */
        @GetMapping("/test/context") public cn.chomy.ai.backend.dto.auth.UserProfile contextUser() {
            return UserContext.require().profile();
        }
        /** 未配置 POST 权限时应拒绝，而不是继承同一路径 GET 权限。 */
        @PostMapping("/test/protected") public String write() { return "ok"; }
        /** 请求级权限通过后，方法级缺失权限仍必须返回 403。 */
        @GetMapping("/test/method") @PreAuthorize("hasAuthority('not:granted')")
        public String method() { return "unreachable"; }
    }

    /**
     * 模拟数据库返回独立实体、白名单更新及 Redis 会话读写，不替换认证授权逻辑。
     * @author chomy
     */
    @SuppressWarnings("unchecked")
    static class TestStore {
        final User user = new User();
        final Permission permission = new Permission();
        /** 项目业务调用边界，检验 Controller 只传入 JWT 用户 ID。 */
        final AgentProjectService agentProjects = mock(AgentProjectService.class);
        /** 对话业务调用边界。 */
        final AgentChatService agentChats = mock(AgentChatService.class);
        /** Chat 级历史与订阅业务边界。 */
        final SessionMessageService sessionMessages = mock(SessionMessageService.class);
        final AdminUserService adminUsers = mock(AdminUserService.class);
        final UserMapper users = mock(UserMapper.class);
        final UserRoleMapper userRoles = mock(UserRoleMapper.class);
        final RoleMapper roles = mock(RoleMapper.class);
        final RolePermissionMapper rolePermissions = mock(RolePermissionMapper.class);
        final PermissionMapper permissions = mock(PermissionMapper.class);
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> values = mock(ValueOperations.class);
        final Map<String, String> cache = new ConcurrentHashMap<>();

        /** 填充虚构测试账号及授权，数据库更新仅应用 LambdaUpdateWrapper 明确声明的字段。 */
        TestStore() {
            user.setStatus(UserStatus.NORMAL); user.setActived(ActivationStatus.ACTIVE);
            user.setId(1L); user.setUsername("alice"); user.setNickname("测试用户"); user.setPhone("13800000000"); user.setGender(Gender.UNKNOWN);
            user.setSignature("原签名");
            Role role = new Role(); role.setId(10L); role.setRoleCode("user"); role.setStatus(EnableStatus.ENABLED);
            UserRole userRole = new UserRole(); userRole.setUserId(1L); userRole.setRoleId(10L);
            RolePermission rp = new RolePermission(); rp.setRoleId(10L); rp.setPermissionId(20L);
            permission.setId(20L); permission.setPermissionCode("demo:read"); permission.setPermissionType(PermissionType.API);
            permission.setStatus(EnableStatus.ENABLED); permission.setApiPath("/test/**"); permission.setHttpMethod("GET");
            when(users.selectOne(any(Wrapper.class))).thenAnswer(call -> {
                Wrapper<User> wrapper = call.getArgument(0);
                wrapper.getSqlSegment();
                if (((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                        .getParamNameValuePairs().containsValue("missing")) { return null; }
                return copyUser();
            });
            when(users.selectById(any())).thenAnswer(call -> copyUser());
            when(users.update(any(Wrapper.class))).thenAnswer(call -> {
                LambdaUpdateWrapper<User> wrapper = call.getArgument(0);
                var matcher = Pattern.compile("([a-z_]+)=#\\{ew.paramNameValuePairs.(MPGENVAL\\d+)\\}").matcher(wrapper.getSqlSet());
                BeanWrapperImpl bean = new BeanWrapperImpl(user);
                while (matcher.find()) {
                    String[] parts = matcher.group(1).split("_"); StringBuilder property = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length; i++) { property.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1)); }
                    bean.setPropertyValue(property.toString(), wrapper.getParamNameValuePairs().get(matcher.group(2)));
                }
                return 1;
            });
            when(userRoles.selectList(any(Wrapper.class))).thenReturn(List.of(userRole));
            when(roles.selectList(any(Wrapper.class))).thenReturn(List.of(role));
            when(rolePermissions.selectList(any(Wrapper.class))).thenReturn(List.of(rp));
            when(permissions.selectList(any(Wrapper.class))).thenReturn(List.of(permission));
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(call -> cache.get(call.getArgument(0)));
            doAnswer(call -> { cache.put(call.getArgument(0), call.getArgument(1));
                assertThat((Duration) call.getArgument(2)).isPositive(); return null; })
                    .when(values).set(anyString(), anyString(), any(Duration.class));
            when(redis.delete(anyString())).thenAnswer(call -> cache.remove(call.getArgument(0)) != null);
            when(redis.delete(anyCollection())).thenAnswer(call -> {
                Collection<String> keys = call.getArgument(0);
                return keys.stream().filter(key -> cache.remove(key) != null).count();
            });
        }

        /** 每次查询返回新对象，模拟数据库行为，防止清除认证凭据时修改测试数据库状态。 */
        User copyUser() { User result = new User(); BeanUtils.copyProperties(user, result); return result; }
    }
}
