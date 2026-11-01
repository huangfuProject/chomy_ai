package cn.chomy.ai.backend.security;

import java.time.Instant;
import java.util.List;
import cn.chomy.ai.backend.common.web.GlobalResponseAdvice;
import cn.chomy.ai.backend.common.web.RequestTimingFilter;
import cn.chomy.ai.backend.config.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 单独验证 MVC 登录拦截器，防止测试仅被前置安全链拦截而没有实际执行拦截器分支。
 * 真实 JWT 验签、Redis 会话及 RBAC 仍由 SecurityIntegrationTest 进行完整链验证。
 *
 * @author chomy
 */
class LoginInterceptorTest {
    /** 当前测试使用的拦截器，直接调用时可核对预检及上下文路径分支。 */
    private LoginInterceptor interceptor;
    /** 不安装 Spring Security 过滤器链，确保未登录响应确实由 MVC 拦截器产生。 */
    private MockMvc mvc;

    /** 为每个测试构建独立请求链并清理前次认证，避免线程上下文干扰结果。 */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        ObjectMapper mapper = new ObjectMapper();
        interceptor = new LoginInterceptor(new SecurityConfig().publicEndpointMatcher(), new SecurityResponseHandler(mapper));
        mvc = MockMvcBuilders.standaloneSetup(new FixtureController())
                .setControllerAdvice(new GlobalResponseAdvice(mapper))
                .addFilters(new RequestTimingFilter()).addInterceptors(interceptor).build();
    }

    /** 所有测试完成后清理当前线程认证，不能污染后续接口及安全链测试。 */
    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    /** 无认证上下文或只有未经验证的请求头均返回统一 401，不执行受保护控制器。 */
    @Test
    void rejectsMissingAuthenticationAndUnverifiedHeaders() throws Exception {
        mvc.perform(get("/private")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401)).andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/private").header("Authorization", "Bearer test-only-unverified")
                        .header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
    }

    /** 只允许已认证的 JWT，匿名主体、未认证 JWT 和其他类型认证均不视为当前系统登录。 */
    @Test
    void acceptsOnlyAuthenticatedJwtContext() throws Exception {
        JwtAuthenticationToken jwt = new JwtAuthenticationToken(token(), List.of());
        setAuthentication(jwt);
        mvc.perform(get("/private")).andExpect(status().isOk()).andExpect(jsonPath("$.data").value("secret"));

        jwt.setAuthenticated(false);
        mvc.perform(get("/private")).andExpect(status().isUnauthorized());
        setAuthentication(new AnonymousAuthenticationToken("test-only", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        mvc.perform(get("/private")).andExpect(status().isUnauthorized());
        setAuthentication(UsernamePasswordAuthenticationToken.authenticated("test-only", null, List.of()));
        mvc.perform(get("/private")).andExpect(status().isUnauthorized());
    }

    /** 白名单同时限制方法：POST 登录和 GET 健康检查公开，反向方法仍须登录。 */
    @Test
    void publicEndpointsAreMethodSensitive() throws Exception {
        mvc.perform(post("/user/login")).andExpect(status().isOk());
        mvc.perform(get("/health/health")).andExpect(status().isOk());
        mvc.perform(get("/user/login")).andExpect(status().isUnauthorized());
        mvc.perform(post("/health/health")).andExpect(status().isUnauthorized());
    }

    /** 合法预检可以通过，普通 OPTIONS 不能借此跳过认证，contextPath 不影响公开匹配。 */
    @Test
    void handlesPreflightAndApplicationContextPath() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/private");
        preflight.addHeader("Origin", "https://frontend.example");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        assertThat(interceptor.preHandle(preflight, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest("OPTIONS", "/private"), denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(401);

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/app/health/health");
        health.setContextPath("/app");
        assertThat(interceptor.preHandle(health, new MockHttpServletResponse(), new Object())).isTrue();
    }

    /** 保存已由测试明确提供的认证主体，模拟安全链完成认证后的上下文。 */
    private void setAuthentication(Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /** 构造仅用于状态检查的 JWT；不声称此测试数据已通过签名验证。 */
    private Jwt token() {
        return Jwt.withTokenValue("test-only").header("alg", "HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    /**
     * 只用于判断控制器是否被允许执行，包含故意设置的白名单反向方法。
     *
     * @author chomy
     */
    @RestController
    static class FixtureController {
        /** 受保护资源，用返回值证明控制器是否执行。 */
        @GetMapping("/private") public String protectedResource() { return "secret"; }
        /** 模拟公开登录入口，不执行任何真实认证或数据库操作。 */
        @PostMapping("/user/login") public String login() { return "login"; }
        /** 同一路径 GET 不属于公开登录规则。 */
        @GetMapping("/user/login") public String otherLoginMethod() { return "private"; }
        /** 公开健康检查。 */
        @GetMapping("/health/health") public String health() { return "ok"; }
        /** 同一路径 POST 不属于公开健康检查规则。 */
        @PostMapping("/health/health") public String otherHealthMethod() { return "private"; }
    }
}
